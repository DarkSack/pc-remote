package com.sack.pcremote.net

import android.util.Log
import com.sack.pcremote.data.AgentCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
//   - Un socket medio muerto (el móvil durmió, la Wi-Fi cambió, el PC
//     se suspendió) seguía marcado CONNECTED hasta que el ping de OkHttp
//     se daba cuenta, 15-30 s después; mientras, todo daba timeout. Ahora
//     un ping de aplicación cada 5 s sin respuesta en 4 s fuerza la
//     reconexión, y ensureAlive() lo comprueba al volver a la app.
//   - Al volver, retryNow() salta la espera del backoff (hasta 30 s).
//   - updateAddress(): si el PC cambió de IP, la sesión lo busca por mDNS
//     y reconecta a la nueva dirección.
//   - Un único OkHttpClient para todas las reconexiones (antes se creaba
//     uno por intento y se apagaba su dispatcher justo después).
// ══════════════════════════════════════════════════════════════

enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, FAILED }

/** The agent presented a certificate other than the one pinned at pairing. */
class CertificateMismatchException(message: String) : java.security.cert.CertificateException(message)

private fun sha256Hex(cert: X509Certificate): String =
    Crypto.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded))

class AgentClient(initial: AgentCredentials) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile private var creds: AgentCredentials = initial
    val host: String get() = creds.agentHost

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _error = MutableStateFlow<AgentError?>(null)
    val error: StateFlow<AgentError?> = _error.asStateFlow()

    /** Round trip of the last application ping, in ms. */
    private val _latency = MutableStateFlow<Long?>(null)
    val latency: StateFlow<Long?> = _latency.asStateFlow()

    /** When the current session authenticated (epoch ms). */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** Consecutive failed attempts since the last successful auth. */
    private val _failures = MutableStateFlow(0)
    val failures: StateFlow<Int> = _failures.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ResponseMsg>>()
    private val streams = ConcurrentHashMap<String, Stream>()

    private class Stream(val onData: (JsonElement) -> Unit, val onError: ((RequestException) -> Unit)?)

    // Every callback checks `webSocket !== ws` and bails out: the listener is
    // shared, and a socket that already died could otherwise fire onFailure after
    // its replacement opened and start a second, parallel reconnect loop.
    @Volatile private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var backoffIndex = 0
    private val backoff = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)

    /** Ping sent and not answered yet (monotonic ms), or 0. */
    @Volatile private var pingSentAt = 0L
    @Volatile private var pongWaiter: CompletableDeferred<Unit>? = null

    private val http: OkHttpClient by lazy { buildOkHttp { creds.certFingerprintHex } }

    @Synchronized
    fun connect() {
        // Only from a resting state: calling it while connecting, authenticating or
        // waiting to reconnect would open a second socket next to the first.
        if (_state.value != ConnectionState.DISCONNECTED && _state.value != ConnectionState.FAILED) return
        backoffIndex = 0
        _failures.value = 0
        openSocket(initial = true)
    }

    /**
     * Try now, whatever we were doing: user pressed "Reintentar", the app came back
     * to the foreground, or the network just came up. Resets the backoff.
     * A FAILED state (revoked, other certificate) is retried too: the user asked.
     */
    @Synchronized
    fun retryNow() {
        when (_state.value) {
            ConnectionState.CONNECTED, ConnectionState.AUTHENTICATING -> return
            ConnectionState.CONNECTING -> return
            else -> {}
        }
        reconnectJob?.cancel()
        reconnectJob = null
        backoffIndex = 0
        dropSocket()
        openSocket(initial = _state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED)
    }

    /** Network back (Wi-Fi reconnected): only acts if we are waiting out a backoff delay. */
    @Synchronized
    fun reconnectNow() {
        if (_state.value != ConnectionState.RECONNECTING || reconnectJob?.isActive != true) return
        retryNow()
    }

    /**
     * Back from the background: a socket that looks open may be dead (the phone
     * slept, the PC suspended). One ping settles it; no pong in 3 s → reconnect.
     */
    fun ensureAlive() {
        when (_state.value) {
            ConnectionState.CONNECTED -> scope.launch {
                if (!ping(3000)) {
                    Log.i(TAG, "No pong after resume; reconnecting")
                    forceReconnect(AgentError.Unresponsive)
                }
            }
            ConnectionState.RECONNECTING -> retryNow()
            ConnectionState.DISCONNECTED -> connect()
            else -> {}
        }
    }

    /** The PC was found at another address (DHCP): use it from the next attempt on. */
    @Synchronized
    fun updateAddress(host: String, port: Int) {
        if (host == creds.agentHost && port == creds.agentPort) return
        creds = creds.copy(agentHost = host, agentPort = port)
        if (_state.value != ConnectionState.CONNECTED) retryNow()
    }

    @Synchronized
    fun disconnect() {
        // State first, so the onClosed that follows does not schedule a reconnect.
        _state.value = ConnectionState.DISCONNECTED
        _connectedSince.value = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        dropSocket()
    }

    /** Disconnect for good: the owner (ViewModel) is going away. */
    fun close() {
        disconnect()
        scope.cancel()
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun dropSocket() {
        val old = ws ?: return
        ws = null
        // close() only works on an open socket; one still handshaking must be cancelled
        // or it opens later, unowned, and the agent keeps it until its auth timeout.
        if (!old.close(1000, "bye")) old.cancel()
    }

    /** States from which no automatic reconnect should happen. */
    private fun isTerminal() =
        _state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED

    /** FAILED is final until the user acts: retrying a revoked device forever helps nobody. */
    private fun fail(error: AgentError) {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        _error.value = error
        _connectedSince.value = null
        _state.value = ConnectionState.FAILED
    }

    private fun openSocket(initial: Boolean) {
        _state.value = if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

        // A malformed saved host, or an IPv6 literal without brackets, makes
        // Request.Builder.url throw. Uncaught, that crashed the app from the UI thread.
        val req = runCatching { Request.Builder().url(agentWsUrl(creds.agentHost, creds.agentPort)).build() }
            .getOrElse {
                fail(AgentError("Dirección no válida", "La dirección guardada (${creds.agentHost}) no es válida.", it.message, permanent = true))
                return
            }
        ws = http.newWebSocket(req, listener)
    }

    @Synchronized
    private fun forceReconnect(reason: AgentError) {
        if (_state.value != ConnectionState.CONNECTED) return
        _error.value = reason
        _connectedSince.value = null
        heartbeatJob?.cancel()
        failAllPending(RuntimeException(reason.title))
        dropSocket()
        _failures.value += 1
        scheduleReconnect(immediate = true)
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
            if (code == CLOSE_REVOKED) fail(AgentError.Revoked)
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            Log.w(TAG, "WS failure: ${t.message}")
            failAllPending(t)
            if (isTerminal()) return
            val err = AgentError.from(t)
            // A certificate that does not match the pinned one will not start
            // matching on the next attempt: stop and tell the user.
            if (err.permanent) { fail(err); return }
            _error.value = err
            _connectedSince.value = null
            _failures.value += 1
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            Log.i(TAG, "WS closed $code $reason")
            failAllPending(RuntimeException("closed: $reason"))
            _connectedSince.value = null
            if (!isTerminal()) {
                _error.value = AgentError("Conexión cerrada", "El PC cerró la conexión. Reconectando…", "close $code $reason")
                _failures.value += 1
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
            MsgKinds.AuthResult -> {
                val ok = root["success"]?.jsonPrimitive?.boolean == true
                if (ok) {
                    backoffIndex = 0
                    _failures.value = 0
                    _error.value = null
                    _connectedSince.value = System.currentTimeMillis()
                    _state.value = ConnectionState.CONNECTED
                    startHeartbeat()
                } else {
                    val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "auth failed"
                    fail(if (msg.contains("revoked", ignoreCase = true) || msg.contains("not paired", ignoreCase = true))
                        AgentError.Revoked.copy(technical = msg) else AgentError.auth(msg))
                    socket.close(1000, "auth")
                }
            }
            MsgKinds.Response -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                if (id == "__probe__" || id.startsWith("fire_")) return
                val res = runCatching { json.decodeFromJsonElement<ResponseMsg>(root) }.getOrNull() ?: return
                pending.remove(id)?.complete(res)
                // A subscribe answered with an error (feature disabled, unknown action).
                if (!res.success && id.startsWith("sub_")) {
                    streams.remove(id)?.onError?.invoke(RequestException(res.error?.code ?: "ERROR", res.error?.message ?: "Error"))
                }
            }
            MsgKinds.Stream -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                val data = root["data"] ?: return
                streams[id]?.onData?.invoke(data)
            }
            MsgKinds.Pong -> {
                val sent = pingSentAt
                if (sent != 0L) {
                    _latency.value = System.nanoTime() / 1_000_000 - sent
                    pingSentAt = 0L
                }
                pongWaiter?.complete(Unit)
            }
        }
    }

    /** Application-level ping. True if the pong came back within [timeoutMs]. */
    private suspend fun ping(timeoutMs: Long): Boolean {
        val socket = ws ?: return false
        val waiter = CompletableDeferred<Unit>()
        pongWaiter = waiter
        pingSentAt = System.nanoTime() / 1_000_000
        if (!socket.send("""{"kind":"ping","ts":${System.currentTimeMillis()}}""")) return false
        return withTimeoutOrNull(timeoutMs) { waiter.await() } != null
    }

    /** Every 5 s: latency for the UI, and the watchdog for half-open sockets. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var misses = 0
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                if (ping(4000)) misses = 0
                else if (++misses >= 2) {
                    forceReconnect(AgentError.Unresponsive)
                    return@launch
                }
                delay(5000)
            }
        }
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        reconnectJob?.cancel()
        val delayMs = if (immediate) 0L else backoff[minOf(backoffIndex, backoff.lastIndex)]
        _state.value = ConnectionState.RECONNECTING
        reconnectJob = scope.launch {
            delay(delayMs)
            // Same lock as retryNow: if it cancelled this job while we were waking
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
        pongWaiter?.cancel()
        pingSentAt = 0L
    }

    // ── Public API ─────────────────────────────────────

    /** Raw response; success=false is returned, not thrown. Throws when not connected or on timeout. */
    suspend fun request(domain: String, action: String, params: JsonElement? = null, timeoutMs: Long = 8000): ResponseMsg {
        check(_state.value == ConnectionState.CONNECTED) { "Not connected" }
        val id = "cmd_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<ResponseMsg>()
        pending[id] = deferred
        val sent = ws?.send(json.encodeToString(RequestMsg(
            kind = MsgKinds.Request, id = id, domain = domain, action = action, params = params,
        ))) ?: false
        if (!sent) { pending.remove(id); throw IllegalStateException("Socket not open") }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run { pending.remove(id); throw RequestException("TIMEOUT", "Timeout $domain.$action") }
    }

    /** Like [request] but returns `data`, throwing [RequestException] on success=false. */
    suspend fun call(domain: String, action: String, params: JsonElement? = null, timeoutMs: Long = 8000): JsonElement? {
        val res = request(domain, action, params, timeoutMs)
        if (!res.success) throw RequestException(res.error?.code ?: "ERROR", res.error?.message ?: "Error")
        return res.data
    }

    /**
     * Fire-and-forget request for continuous input (pointer moves, scroll). No
     * pending entry and no timeout. Returns false when not connected.
     */
    fun send(domain: String, action: String, params: JsonElement? = null): Boolean {
        if (_state.value != ConnectionState.CONNECTED) return false
        return ws?.send(json.encodeToString(RequestMsg(
            kind = MsgKinds.Request, id = "fire_${counter.incrementAndGet()}",
            domain = domain, action = action, params = params,
        ))) ?: false
    }

    private val counter = java.util.concurrent.atomic.AtomicLong()

    fun subscribe(
        domain: String, action: String, params: JsonElement? = null,
        onError: ((RequestException) -> Unit)? = null,
        onData: (JsonElement) -> Unit,
    ): Subscription {
        val id = "sub_${UUID.randomUUID()}"
        streams[id] = Stream(onData, onError)
        ws?.send(json.encodeToString(SubscribeMsg(
            id = id, domain = domain, action = action, params = params,
        )))
        return Subscription(id) {
            if (streams.remove(id) != null) ws?.send(json.encodeToString(UnsubscribeMsg(id = id)))
        }
    }

    class Subscription(val id: String, val cancel: () -> Unit)

    // ── Helpers ─────────────────────────────────────────

    private fun buildOkHttp(pinned: () -> String): OkHttpClient {
        // TrustManager que acepta cualquier cert y compara el SHA-256 con el emparejado.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no cert")
                val got  = sha256Hex(cert)
                val expected = pinned()
                if (!got.equals(expected, ignoreCase = true)) {
                    throw CertificateMismatchException("Cert fingerprint mismatch: got=$got expected=$expected")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            // Our own heartbeat detects dead sockets; OkHttp's ping stays as a backstop.
            .pingInterval(20, TimeUnit.SECONDS)
            // Big frames: app icons, clipboard images, file chunks.
            .readTimeout(0, TimeUnit.MILLISECONDS)
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
