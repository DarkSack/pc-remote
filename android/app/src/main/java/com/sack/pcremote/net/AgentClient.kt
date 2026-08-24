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

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffIndex = 0
    private val backoff = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)

    fun connect() {
        if (_state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.CONNECTING) return
        openSocket(initial = true)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "bye")
        ws = null
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun openSocket(initial: Boolean) {
        _state.value = if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

        val client = buildOkHttp(creds.certFingerprintHex)
        val req = Request.Builder()
            .url("wss://${creds.agentHost}:${creds.agentPort}/ws")
            .build()

        ws = client.newWebSocket(req, listener)
        client.dispatcher.executorService.shutdown()   // don't keep pool alive
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open, sending probe to trigger auth_challenge")
            _state.value = ConnectionState.AUTHENTICATING
            // Sending any request without a session triggers the challenge.
            webSocket.send(json.encodeToString(
                RequestMsg(kind = MsgKinds.Request, id = "__probe__", domain = "ping", action = "ping"),
            ))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            _error.value = t.message
            failAllPending(t)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed $code $reason")
            failAllPending(RuntimeException("closed: $reason"))
            if (_state.value != ConnectionState.DISCONNECTED) scheduleReconnect()
        }
    }

    private fun handleFrame(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root["kind"]?.jsonPrimitive?.content) {
            MsgKinds.AuthChallenge -> {
                val nonceHex = root["nonce"]?.jsonPrimitive?.content ?: return
                val nonce = Crypto.fromHex(nonceHex)
                val priv  = Crypto.fromB64(creds.privateSeedB64)
                val sig   = Crypto.sign(priv, nonce)
                ws?.send(json.encodeToString(AuthMsg(
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
                    _error.value = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "auth failed"
                    _state.value = ConnectionState.FAILED
                    ws?.close(1000, "auth")
                }
            }
            MsgKinds.Response -> {
                val id = root["id"]?.jsonPrimitive?.content ?: return
                if (id == "__probe__") return
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
            backoffIndex = minOf(backoffIndex + 1, backoff.lastIndex)
            openSocket(initial = false)
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
                val cert = chain.firstOrNull() ?: throw RuntimeException("no cert")
                val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                val got    = Crypto.toHex(sha256)
                if (!got.equals(pinnedFingerprintHex, ignoreCase = true)) {
                    throw RuntimeException("Cert fingerprint mismatch: got=$got expected=$pinnedFingerprintHex")
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

    companion object { private const val TAG = "AgentClient" }
}

/** Cliente "one-shot" para pairing (sin credenciales pre-existentes). */
class PairingClient(
    private val host: String,
    private val port: Int,
    private val agentName: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ws: WebSocket? = null
    private var keys: Crypto.Keypair? = null
    private var onPhase: ((PairPhase, String?) -> Unit)? = null

    fun start(onPhase: (PairPhase, String?) -> Unit) {
        this.onPhase = onPhase
        onPhase(PairPhase.CONNECTING, null)
        keys = Crypto.generateKeypair()

        // TLS: accept anything — no fingerprint yet.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        val req = Request.Builder().url("wss://$host:$port/ws").build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(json.encodeToString(PairInitMsg()))
            }
            override fun onMessage(ws: WebSocket, text: String) = handle(text)
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                onPhase(PairPhase.ERROR, t.message)
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

    fun cancel() { ws?.close(1000, "cancel"); ws = null }

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
                    val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    onPhase?.invoke(PairPhase.ERROR, msg ?: "Pair failed")
                    return
                }
                val deviceId = root["deviceId"]?.jsonPrimitive?.content ?: return
                val fp       = root["certFingerprint"]?.jsonPrimitive?.content ?: return
                val k = keys ?: return
                lastResult = AgentCredentials(
                    deviceId           = deviceId,
                    privateSeedB64     = Crypto.b64(k.privateSeed),
                    publicKeyB64       = Crypto.b64(k.publicKey),
                    agentHost          = host,
                    agentPort          = port,
                    agentName          = agentName,
                    certFingerprintHex = fp,
                )
                onPhase?.invoke(PairPhase.DONE, deviceId)
                ws?.close(1000, "ok")
            }
        }
    }
}

enum class PairPhase { CONNECTING, WAITING_CODE, CONFIRMING, DONE, ERROR }
