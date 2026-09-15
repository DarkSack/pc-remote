package com.sack.pcremote.net

import android.util.Base64
import android.util.Log
import com.sack.pcremote.data.AgentCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
// Firmamos con Ed25519 y respondemos con `auth`. Server valida
// contra la public key del device en su SQLite.
//
// API pública:
//   connect() / disconnect()
//   state: StateFlow<ConnectionState>
//   request(domain, action, params?) → Response suspendible
//   subscribe(domain, action, params?, onData) → Sub cancelable
// ══════════════════════════════════════════════════════════════

enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, FAILED }

/** The agent presented a certificate other than the one pinned at pairing. */
class CertificateMismatchException(message: String) : java.security.cert.CertificateException(message)

private fun sha256Hex(cert: X509Certificate): String =
    Crypto.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded))

class AgentClient(private val creds: AgentCredentials) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"   // no lo usamos pero por si acaso
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ResponseMsg>>()
    private val streams = ConcurrentHashMap<String, (JsonElement) -> Unit>()

    // Every callback checks `webSocket !== ws` and bails out: the listener is
    // shared, and a socket that already died could otherwise fire onFailure after
    // its replacement opened and start a second, parallel reconnect loop.
    @Volatile private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffIndex = 0
    private val backoff = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)

    fun connect() {
        // Only from a resting state: calling it while connecting, authenticating or
        // waiting to reconnect would open a second socket next to the first.
        if (_state.value != ConnectionState.DISCONNECTED && _state.value != ConnectionState.FAILED) return
        backoffIndex = 0
        openSocket(initial = true)
    }

    fun disconnect() {
        // State first, so the onClosed that follows does not schedule a reconnect.
        _state.value = ConnectionState.DISCONNECTED
        reconnectJob?.cancel()
        reconnectJob = null
        val old = ws
        ws = null
        old?.close(1000, "bye")
    }

    /**
     * A network just came up (Wi-Fi back, switched networks). If we are waiting out
     * a backoff delay — up to 30 s — try right away instead. Does nothing in any
     * other state, so it is safe to call on every network callback.
     */
    @Synchronized
    fun reconnectNow() {
        if (_state.value != ConnectionState.RECONNECTING || reconnectJob?.isActive != true) return
        reconnectJob?.cancel()
        reconnectJob = null
        backoffIndex = 0
        openSocket(initial = false)
    }

    /** States from which no automatic reconnect should happen. */
    private fun isTerminal() =
        _state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED

    /** FAILED is final until the user acts: retrying a revoked device forever helps nobody. */
    private fun fail(message: String) {
        reconnectJob?.cancel()
        _error.value = message
        _state.value = ConnectionState.FAILED
    }

    private fun openSocket(initial: Boolean) {
        _state.value = if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

        // A malformed saved host, or an IPv6 literal without brackets, makes
        // Request.Builder.url throw. Uncaught, that crashed the app from the UI
        // thread; now it is a visible, final error.
        val req = runCatching { Request.Builder().url(agentWsUrl(creds.agentHost, creds.agentPort)).build() }
            .getOrElse { fail("Dirección del PC no válida: ${creds.agentHost}"); return }
        val client = buildOkHttp(creds.certFingerprintHex)

        ws = client.newWebSocket(req, listener)
        client.dispatcher.executorService.shutdown()   // don't keep pool alive
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
            if (code == CLOSE_REVOKED) fail("Este dispositivo ya no está autorizado en el PC. Vuelve a emparejarlo.")
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            Log.w(TAG, "WS failure: ${t.message}")
            failAllPending(t)
            if (isTerminal()) return
            // A certificate that does not match the pinned one will not start
            // matching on the next attempt: stop and tell the user.
            if (generateSequence(t) { it.cause }.any { it is CertificateMismatchException }) {
                fail("El certificado del PC no coincide con el emparejado. ¿Reinstalaste el agente? Vuelve a emparejar.")
                return
            }
            _error.value = t.message
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            Log.i(TAG, "WS closed $code $reason")
            failAllPending(RuntimeException("closed: $reason"))
            if (!isTerminal()) scheduleReconnect()
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
                    _state.value = ConnectionState.CONNECTED
                    _error.value = null
                } else {
                    fail(root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "auth failed")
                    socket.close(1000, "auth")
                }
            }
            MsgKinds.Response -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                if (id == "__probe__" || id.startsWith("fire_")) return
                val res = json.decodeFromJsonElement<ResponseMsg>(root)
                pending.remove(id)?.complete(res)
            }
            MsgKinds.Stream -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                val data = root["data"] ?: return
                streams[id]?.invoke(data)
            }
            MsgKinds.Pong -> {} // TODO: implement ping tracking
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = backoff[minOf(backoffIndex, backoff.lastIndex)]
        _state.value = ConnectionState.RECONNECTING
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
        check(_state.value == ConnectionState.CONNECTED) { "Not connected" }
        val id = "cmd_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<ResponseMsg>()
        pending[id] = deferred
        ws?.send(json.encodeToString(RequestMsg(
            kind = MsgKinds.Request, id = id, domain = domain, action = action, params = params,
        ))) ?: run { pending.remove(id); throw IllegalStateException("Socket not open") }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run { pending.remove(id); throw RuntimeException("Timeout $domain.$action") }
    }

    /**
     * Fire-and-forget request for continuous input (pointer moves, scroll). No
     * pending entry and no timeout: at 60 moves a second, waiting on each answer
     * would only add latency and fill `pending`. The response still arrives and is
     * simply ignored. Returns false when not connected.
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
        onData: (JsonElement) -> Unit,
    ): Subscription {
        val id = "sub_${UUID.randomUUID()}"
        streams[id] = onData
        ws?.send(json.encodeToString(SubscribeMsg(
            id = id, domain = domain, action = action, params = params,
        )))
        return Subscription(id) {
            streams.remove(id)
            ws?.send(json.encodeToString(UnsubscribeMsg(id = id)))
        }
    }

    class Subscription(val id: String, val cancel: () -> Unit)

    // ── Helpers ─────────────────────────────────────────

    private fun buildOkHttp(pinnedFingerprintHex: String): OkHttpClient {
        // TrustManager que acepta cualquier cert. La validación real la hace
        // el interceptor comparando SHA-256 con el fingerprint pinned.
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
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
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
