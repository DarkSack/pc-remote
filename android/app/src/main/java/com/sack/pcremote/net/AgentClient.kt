package com.sack.pcremote.net

import android.os.SystemClock
import android.util.Log
import com.sack.pcremote.data.AgentCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ══════════════════════════════════════════════════════════════
// Cliente WSS que habla el protocolo del agente.
//
// TLS: acepta cualquier cert (self-signed) y luego compara el
// SHA-256 con el fingerprint almacenado al emparejar. Rechaza si
// no coincide → esto es el "cert pinning" real.
//
// Auth: al abrir, el server emite `auth_challenge` con un nonce.
// Firmamos con Ed25519 y respondemos con `auth`.
//
// Reconexión (lo que fallaba "al volver"):
//   - Android congela la app en segundo plano. Al volver, el socket puede
//     seguir marcado como abierto pero estar muerto (el PC lo cerró, la
//     Wi-Fi durmió). restart() lo descarta y abre otro YA, sin esperar a
//     que OkHttp note el fallo ni a la cola de backoff.
//   - ping() mide la latencia con ping.ping; PcSession lo usa para
//     detectar esos sockets zombis mientras la app está abierta.
//   - updateEndpoint(): si el PC cambió de IP (DHCP), PcSession lo
//     encuentra por mDNS y el siguiente intento va a la dirección nueva.
//   - Los errores se traducen a ConnectionProblem (texto para personas).
//
// API pública:
//   connect() / disconnect() / reconnectNow() / restart() / close()
//   state, problem, failures, connectedSince: StateFlow
//   request(domain, action, params?) → Response suspendible
//   send(...)       → fire-and-forget (ratón)
//   subscribe(...)  → Subscription cancelable
//   stream(...)     → Flow que se re-suscribe solo tras cada reconexión
// ══════════════════════════════════════════════════════════════

enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, FAILED }

/** The agent presented a certificate other than the one pinned at pairing. */
class CertificateMismatchException(message: String) : java.security.cert.CertificateException(message)

/** A command the agent answered with success=false. */
class AgentException(val code: String, message: String) : RuntimeException(message)

private fun sha256Hex(cert: X509Certificate): String =
    Crypto.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded))

class AgentClient(initial: AgentCredentials) {

    @Volatile var creds: AgentCredentials = initial
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _problem = MutableStateFlow<ConnectionProblem?>(null)
    /** Why the last attempt failed; null while connected. */
    val problem: StateFlow<ConnectionProblem?> = _problem.asStateFlow()

    private val _failures = MutableStateFlow(0)
    /** Failed attempts in a row since the last successful connection. */
    val failures: StateFlow<Int> = _failures.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    /** Wall-clock ms of the current connection's start, for "conectado hace…". */
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ResponseMsg>>()
    private val streams = ConcurrentHashMap<String, (JsonElement) -> Unit>()
    private val counter = AtomicLong()

    // Every callback checks `webSocket !== ws` and bails out: the listener is
    // shared, and a socket that already died could otherwise fire onFailure after
    // its replacement opened and start a second, parallel reconnect loop.
    @Volatile private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffIndex = 0
    private val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 20_000)

    /** One client per PC (one connection pool, one dispatcher), built on first use. */
    private val http: OkHttpClient by lazy { buildOkHttp(creds.certFingerprintHex) }

    // ── Lifecycle ─────────────────────────────────────────

    @Synchronized
    fun connect() {
        // Only from a resting state: calling it while connecting, authenticating or
        // waiting to reconnect would open a second socket next to the first.
        if (_state.value != ConnectionState.DISCONNECTED && _state.value != ConnectionState.FAILED) return
        backoffIndex = 0
        openSocket(initial = true)
    }

    @Synchronized
    fun disconnect() {
        // State first, so the onClosed that follows does not schedule a reconnect.
        _state.value = ConnectionState.DISCONNECTED
        _connectedSince.value = null
        reconnectJob?.cancel()
        reconnectJob = null
        val old = ws
        ws = null
        old?.close(1000, "bye")
        failAllPending(IllegalStateException("Desconectado"))
    }

    /**
     * A network just came up. If we are waiting out a backoff delay, try right
     * away. Does nothing in any other state, so it is safe on every network callback.
     */
    @Synchronized
    fun reconnectNow() {
        if (_state.value != ConnectionState.RECONNECTING || reconnectJob?.isActive != true) return
        reconnectJob?.cancel()
        reconnectJob = null
        backoffIndex = 0
        openSocket(initial = false)
    }

    /**
     * Drops whatever socket there is — even one that still looks open — and opens a
     * new one now. For coming back to the app after a while, and for a socket that
     * stopped answering pings. A FAILED client whose problem is final (revoked,
     * wrong certificate) is left alone: only the user can fix that.
     */
    @Synchronized
    fun restart() {
        if (_state.value == ConnectionState.FAILED && _problem.value?.isFinal == true) return
        reconnectJob?.cancel()
        reconnectJob = null
        val old = ws
        ws = null
        old?.cancel()
        failAllPending(IllegalStateException("Reconectando"))
        _connectedSince.value = null
        backoffIndex = 0
        openSocket(initial = _state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED)
    }

    /** The PC moved (new DHCP address, found by mDNS). The next attempt goes there. */
    fun updateEndpoint(host: String, port: Int) {
        creds = creds.copy(agentHost = host, agentPort = port)
    }

    /** Disconnects for good and frees the HTTP client. */
    fun close() {
        disconnect()
        scope.cancel()
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /** States from which no automatic reconnect should happen. */
    private fun isTerminal() =
        _state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED

    /** FAILED is final until the user acts: retrying a revoked device forever helps nobody. */
    private fun fail(problem: ConnectionProblem) {
        reconnectJob?.cancel()
        _problem.value = problem
        _connectedSince.value = null
        _state.value = ConnectionState.FAILED
    }

    private fun openSocket(initial: Boolean) {
        _state.value = if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

        // A malformed saved host, or an IPv6 literal without brackets, makes
        // Request.Builder.url throw: a visible, final error instead of a crash.
        val c = creds
        val req = runCatching { Request.Builder().url(agentWsUrl(c.agentHost, c.agentPort)).build() }
            .getOrElse { fail(ConnectionProblem.badAddress(c.agentHost)); return }

        ws = http.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            Log.i(TAG, "WS open, sending probe to trigger auth_challenge")
            _state.value = ConnectionState.AUTHENTICATING
            // Sending any request without a session triggers the challenge.
            webSocket.send(json.encodeToString(
                RequestMsg(kind = MsgKinds.Request, id = "__probe__", domain = "ping", action = "ping"),
            ))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            handleFrame(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            if (code == CLOSE_REVOKED) synchronized(this@AgentClient) { fail(ConnectionProblem.revoked()) }
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@AgentClient) {
                if (webSocket !== ws) return
                Log.w(TAG, "WS failure: ${t.message}")
                failAllPending(t)
                if (isTerminal()) return
                val problem = ConnectionProblem.from(t)
                // A certificate that does not match the pinned one will not start
                // matching on the next attempt: stop and tell the user.
                if (problem.isFinal) { fail(problem); return }
                _problem.value = problem
                _failures.value = _failures.value + 1
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@AgentClient) {
                if (webSocket !== ws) return
                Log.i(TAG, "WS closed $code $reason")
                failAllPending(RuntimeException("closed: $reason"))
                if (isTerminal()) return
                _problem.value = ConnectionProblem(ConnectionProblem.Kind.CLOSED, "El PC cerró la conexión",
                    "Reconectando…", "close $code $reason")
                scheduleReconnect()
            }
        }
    }

    private fun handleFrame(socket: WebSocket, text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root["kind"]?.jsonPrimitive?.content) {
            MsgKinds.AuthChallenge -> {
                val nonceHex = root["nonce"]?.jsonPrimitive?.content ?: return
                val nonce = Crypto.fromHex(nonceHex)
                val priv  = Crypto.fromB64(creds.privateSeedB64)
                val sig   = Crypto.sign(priv, nonce)
                socket.send(json.encodeToString(AuthMsg(
                    deviceId  = creds.deviceId,
                    signature = Crypto.b64(sig),
                )))
            }
            MsgKinds.AuthResult -> synchronized(this) {
                if (socket !== ws) return
                val ok = root["success"]?.jsonPrimitive?.boolean == true
                if (ok) {
                    backoffIndex = 0
                    _failures.value = 0
                    _problem.value = null
                    _connectedSince.value = System.currentTimeMillis()
                    _state.value = ConnectionState.CONNECTED
                } else {
                    val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    fail(if (msg?.contains("revoked", ignoreCase = true) == true) ConnectionProblem.revoked()
                         else ConnectionProblem.auth(msg))
                    socket.close(1000, "auth")
                }
            }
            MsgKinds.Response -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                if (id == "__probe__" || id.startsWith("fire_")) return
                val res = runCatching { json.decodeFromJsonElement<ResponseMsg>(root) }.getOrNull() ?: return
                pending.remove(id)?.complete(res)
            }
            MsgKinds.Stream -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                val data = root["data"] ?: return
                streams[id]?.invoke(data)
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = backoff[minOf(backoffIndex, backoff.lastIndex)]
        _state.value = ConnectionState.RECONNECTING
        _connectedSince.value = null
        reconnectJob = scope.launch {
            delay(delayMs)
            // Same lock as reconnectNow: if it cancelled this job while we were waking
            // up, isActive is false here and only its socket gets opened, not two.
            synchronized(this@AgentClient) {
                if (!isActive || isTerminal()) return@launch
                backoffIndex = minOf(backoffIndex + 1, backoff.lastIndex)
                openSocket(initial = false)
            }
        }
    }

    private fun failAllPending(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        streams.clear()
    }

    // ── Public API ─────────────────────────────────────

    suspend fun request(domain: String, action: String, params: JsonElement? = null, timeoutMs: Long = 8000): ResponseMsg {
        check(_state.value == ConnectionState.CONNECTED) { "Sin conexión con el PC" }
        val id = "cmd_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<ResponseMsg>()
        pending[id] = deferred
        val sent = ws?.send(json.encodeToString(RequestMsg(
            kind = MsgKinds.Request, id = id, domain = domain, action = action, params = params,
        ))) ?: false
        if (!sent) { pending.remove(id); throw IllegalStateException("Sin conexión con el PC") }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run { pending.remove(id); throw RuntimeException("El PC no respondió a tiempo ($domain.$action)") }
    }

    /**
     * request() + decode. Failure carries the agent's message (AgentException) or
     * the transport error, so screens can show it without extra plumbing.
     */
    suspend fun <T> call(
        domain: String, action: String, serializer: KSerializer<T>,
        params: JsonElement? = null, timeoutMs: Long = 8000,
    ): Result<T> = runCatching {
        val res = request(domain, action, params, timeoutMs)
        if (!res.success) throw AgentException(res.error?.code ?: "ERROR", res.error?.message ?: "Error del agente")
        json.decodeFromJsonElement(serializer, res.data ?: JsonNull)
    }

    /** Round trip of ping.ping in ms, or null if it did not come back within [timeoutMs]. */
    suspend fun ping(timeoutMs: Long = 3000): Long? {
        if (_state.value != ConnectionState.CONNECTED) return null
        val start = SystemClock.elapsedRealtime()
        return runCatching { request("ping", "ping", timeoutMs = timeoutMs) }
            .getOrNull()?.takeIf { it.success }?.let { SystemClock.elapsedRealtime() - start }
    }

    /**
     * Fire-and-forget request for continuous input (pointer moves, scroll). No
     * pending entry and no timeout: at 60 moves a second, waiting on each answer
     * would only add latency and fill `pending`. Returns false when not connected.
     */
    fun send(domain: String, action: String, params: JsonElement? = null): Boolean {
        if (_state.value != ConnectionState.CONNECTED) return false
        return ws?.send(json.encodeToString(RequestMsg(
            kind = MsgKinds.Request, id = "fire_${counter.incrementAndGet()}",
            domain = domain, action = action, params = params,
        ))) ?: false
    }

    fun subscribe(
        domain: String, action: String, params: JsonElement? = null,
        onData: (JsonElement) -> Unit,
    ): Subscription {
        val id = "sub_${UUID.randomUUID()}"
        streams[id] = onData
        val socket = ws
        socket?.send(json.encodeToString(SubscribeMsg(
            id = id, domain = domain, action = action, params = params,
        )))
        return Subscription(id) {
            streams.remove(id)
            // Only on the socket it was made on: after a reconnect the new socket
            // never heard of this id.
            if (ws === socket) socket?.send(json.encodeToString(UnsubscribeMsg(id = id)))
        }
    }

    class Subscription(val id: String, val cancel: () -> Unit)

    /**
     * A stream as a Flow: subscribes while connected, and again after every
     * reconnect (streams die with their socket). Collect it for as long as the
     * data is wanted; cancelling the collector unsubscribes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stream(domain: String, action: String, params: JsonElement? = null): Flow<JsonElement> =
        state.map { it == ConnectionState.CONNECTED }
            .distinctUntilChanged()
            .flatMapLatest { connected ->
                if (!connected) emptyFlow()
                else callbackFlow {
                    val sub = subscribe(domain, action, params) { trySend(it) }
                    awaitClose { sub.cancel() }
                }
            }

    fun <T> decode(serializer: KSerializer<T>, data: JsonElement): T? =
        runCatching { json.decodeFromJsonElement(serializer, data) }.getOrNull()

    // ── Helpers ─────────────────────────────────────────

    private fun buildOkHttp(pinnedFingerprintHex: String): OkHttpClient {
        // TrustManager que acepta cualquier cert. La validación real la hace
        // checkServerTrusted comparando SHA-256 con el fingerprint pinned.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no cert")
                val got  = sha256Hex(cert)
                if (!got.equals(pinnedFingerprintHex, ignoreCase = true)) {
                    throw CertificateMismatchException("Cert fingerprint mismatch: got=$got expected=$pinnedFingerprintHex")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            // A LAN answers in milliseconds: fail fast and let the backoff retry,
            // instead of hanging 10 s on a PC that is asleep.
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "AgentClient"
        /** Close code the agent uses for a revoked / deleted device. */
        const val CLOSE_REVOKED = 4001
    }
}

/**
 * Cliente "one-shot" para pairing (sin credenciales pre-existentes).
 *
 * Dos modos:
 * - Manual (`expectedFingerprint == null`): manda `pair_init`, el PC muestra un
 *   código y el usuario lo teclea. El certificado se acepta a ciegas y solo se
 *   comprueba contra lo que declare el propio PC (trust-on-first-use).
 * - QR (`expectedFingerprint` viene del QR del panel): el código ya lo trae el
 *   QR, así que no hay `pair_init` (no salta notificación en el PC), y el
 *   handshake TLS se corta si el certificado no es exactamente ese.
 */
class PairingClient(
    private val host: String,
    private val port: Int,
    private val agentName: String,
    private val expectedFingerprint: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ws: WebSocket? = null
    private var keys: Crypto.Keypair? = null
    private var onPhase: ((PairPhase, String?) -> Unit)? = null

    /** SHA-256 of the certificate this TLS session actually used. */
    @Volatile private var seenFingerprint: String? = null

    /** DONE, ERROR or cancelled: later socket callbacks must not change the phase. */
    @Volatile private var finished = false

    private fun finish(phase: PairPhase, info: String?) {
        if (finished) return
        finished = true
        onPhase?.invoke(phase, info)
    }

    fun start(onPhase: (PairPhase, String?) -> Unit) {
        this.onPhase = onPhase
        onPhase(PairPhase.CONNECTING, null)
        keys = Crypto.generateKeypair()

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = chain.firstOrNull()?.let(::sha256Hex)
                seenFingerprint = got
                if (expectedFingerprint != null && !expectedFingerprint.equals(got, ignoreCase = true)) {
                    throw CertificateMismatchException("QR fingerprint $expectedFingerprint, server presented $got")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        val req = runCatching { Request.Builder().url(agentWsUrl(host, port)).build() }
            .getOrElse { onPhase(PairPhase.ERROR, "Dirección no válida: $host"); return }

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (expectedFingerprint == null) webSocket.send(json.encodeToString(PairInitMsg()))
                else onPhase(PairPhase.WAITING_CODE, null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val mismatch = generateSequence(t) { it.cause }.any { it is CertificateMismatchException }
                finish(PairPhase.ERROR,
                    if (mismatch) "El PC de esa dirección no tiene el certificado del QR. Genera un QR nuevo en el panel y vuelve a escanearlo."
                    else t.message)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            // The agent drops sockets that do not finish pairing in time. Without this
            // the screen kept asking for the code, and "Confirmar" silently did nothing.
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(PairPhase.ERROR, "El PC cerró la conexión antes de terminar (¿tardaste demasiado?). Vuelve a intentarlo.")
            }
        })
    }

    fun submitCode(code: String, deviceName: String) {
        val k = keys ?: return
        onPhase?.invoke(PairPhase.CONFIRMING, null)
        ws?.send(json.encodeToString(PairConfirmMsg(
            code       = code,
            deviceName = deviceName,
            publicKey  = Crypto.b64(k.publicKey),
        )))
    }

    fun cancel() { finished = true; ws?.close(1000, "cancel"); ws = null }

    var lastResult: AgentCredentials? = null
        private set

    private fun handle(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root["kind"]?.jsonPrimitive?.content) {
            MsgKinds.PairInitAck -> {
                val ttl = root["ttlSec"]?.jsonPrimitive?.intOrNull ?: 120
                onPhase?.invoke(PairPhase.WAITING_CODE, "TTL: ${ttl}s")
            }
            MsgKinds.PairResult -> {
                val ok = root["success"]?.jsonPrimitive?.boolean == true
                if (!ok) {
                    val err = root["error"]?.jsonObject
                    val msg = err?.get("message")?.jsonPrimitive?.content
                    // A typo in a typed code: let the user fix it on the same socket. The
                    // agent still locks the phone out after MaxAttempts, which arrives as
                    // RATE_LIMITED and ends the flow below. A QR code cannot be retyped.
                    if (err?.get("code")?.jsonPrimitive?.content == "PAIRING_FAILED" && expectedFingerprint == null) {
                        onPhase?.invoke(PairPhase.WAITING_CODE, "Código incorrecto o caducado. Revisa el que muestra el PC.")
                        return
                    }
                    finish(PairPhase.ERROR, msg ?: "Pair failed")
                    return
                }
                val deviceId = root["deviceId"]?.jsonPrimitive?.content ?: return
                val fp       = root["certFingerprint"]?.jsonPrimitive?.content ?: return
                val k = keys ?: return
                // Someone in the middle presents their own certificate. If what the
                // agent says it uses is not what we just talked to, do not pin it.
                if (!fp.equals(seenFingerprint, ignoreCase = true)) {
                    finish(PairPhase.ERROR,
                        "El certificado de la conexión no coincide con el que declara el PC. Emparejamiento cancelado.")
                    ws?.close(1000, "fingerprint")
                    return
                }
                lastResult = AgentCredentials(
                    deviceId           = deviceId,
                    privateSeedB64     = Crypto.b64(k.privateSeed),
                    publicKeyB64       = Crypto.b64(k.publicKey),
                    agentHost          = host,
                    agentPort          = port,
                    agentName          = agentName,
                    certFingerprintHex = fp,
                )
                finish(PairPhase.DONE, deviceId)
                ws?.close(1000, "ok")
            }
        }
    }
}

enum class PairPhase { CONNECTING, WAITING_CODE, CONFIRMING, DONE, ERROR }
