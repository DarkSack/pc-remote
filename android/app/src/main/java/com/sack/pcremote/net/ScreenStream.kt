package com.sack.pcremote.net

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer

// ══════════════════════════════════════════════════════════════
// Pantalla remota: el vídeo del PC por su propio socket.
//
// 1. screen.open por el socket de control (ya autenticado) → ticket.
// 2. wss://pc/socket/screen, primer mensaje {ticket, quality, display}.
// 3. El PC manda config (tamaño, pantallas), vídeo H.264, cursor y
//    estadísticas; nosotros mandamos la entrada (ratón, teclado).
//
// Si el socket cae (Wi-Fi, el PC cerró la sesión) se reabre solo con un
// ticket nuevo en cuanto el control vuelve a estar conectado.
// Formato binario: ver ScreenSession.cs en el agente o docs/PROTOCOL.md.
// ══════════════════════════════════════════════════════════════

@Serializable
data class ScreenDisplay(
    val index: Int,
    val name: String,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int,
    val height: Int,
    val primary: Boolean = false,
)

@Serializable
data class ScreenConfig(
    val codec: String,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val display: ScreenDisplay,
    val displays: List<ScreenDisplay> = emptyList(),
    val encoder: String = "",
    val hardware: Boolean = false,
    val fps: Int = 60,
    val quality: String = "balanced",
)

data class ScreenStats(val fps: Double, val kbps: Int, val bitrateKbps: Int, val encodeMs: Double, val rttMs: Long?)

/** Cursor of the PC in source pixels of the monitor. */
data class RemoteCursor(val x: Int, val y: Int, val visible: Boolean)

class CursorImage(val bitmap: Bitmap, val hotX: Int, val hotY: Int)

sealed interface ScreenState {
    data object Connecting : ScreenState
    data object Streaming : ScreenState
    /** UAC prompt, lock screen: Windows does not let the agent see it. */
    data class Blocked(val message: String) : ScreenState
    data class Failed(val message: String) : ScreenState
    data object Stopped : ScreenState
}

/** Receives encoded video. Called on OkHttp's reader thread. */
fun interface VideoSink {
    fun onFrame(data: ByteBuffer, keyFrame: Boolean, timestampUs: Long)
}

class ScreenStream(private val client: AgentClient) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<ScreenState>(ScreenState.Stopped)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private val _config = MutableStateFlow<ScreenConfig?>(null)
    val config: StateFlow<ScreenConfig?> = _config.asStateFlow()

    private val _stats = MutableStateFlow<ScreenStats?>(null)
    val stats: StateFlow<ScreenStats?> = _stats.asStateFlow()

    private val _cursor = MutableStateFlow(RemoteCursor(0, 0, false))
    val cursor: StateFlow<RemoteCursor> = _cursor.asStateFlow()

    private val _cursorImage = MutableStateFlow<CursorImage?>(null)
    val cursorImage: StateFlow<CursorImage?> = _cursorImage.asStateFlow()

    @Volatile var sink: VideoSink? = null

    @Volatile private var ws: WebSocket? = null
    private var runner: Job? = null
    @Volatile private var quality = "balanced"
    @Volatile private var display: Int? = null
    @Volatile private var rttMs: Long? = null

    /** Opens (or re-opens) the stream. Safe to call again: the previous one closes first. */
    @Synchronized
    fun start(quality: String = this.quality) {
        this.quality = quality
        runner?.cancel()
        runner = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        runner?.cancel()
        runner = null
        ws?.close(1000, "bye")
        ws = null
        _state.value = ScreenState.Stopped
    }

    fun close() {
        stop()
        scope.cancel()
    }

    /** One connection after another until stopped or a permanent error. */
    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = ScreenState.Connecting
            // The ticket comes over the control socket.
            client.state.first { it == ConnectionState.CONNECTED }
            val outcome = runCatching { openOnce() }.getOrElse { e ->
                if (e is CancellationException) throw e
                Outcome.Retry(e.message ?: "Error")
            }
            when (outcome) {
                is Outcome.Fatal -> { _state.value = ScreenState.Failed(outcome.message); return }
                is Outcome.Retry -> {
                    Log.i(TAG, "Screen socket ended: ${outcome.message}")
                    attempt++
                    delay(minOf(500L * attempt, 4000L))
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Retry(val message: String) : Outcome
        data class Fatal(val message: String) : Outcome
    }

    private suspend fun openOnce(): Outcome {
        val res = client.request("screen", "open")
        if (!res.success) {
            val code = res.error?.code
            val msg = res.error?.message ?: "No se pudo abrir la pantalla"
            return if (code == "FEATURE_DISABLED" || code == "INVALID_COMMAND") Outcome.Fatal(
                if (code == "INVALID_COMMAND") "El agente de este PC es antiguo: actualízalo para ver la pantalla." else msg
            ) else Outcome.Retry(msg)
        }
        val data = res.data?.jsonObject ?: return Outcome.Retry("Respuesta vacía")
        val ticket = data["ticket"]?.jsonPrimitive?.content ?: return Outcome.Retry("Sin ticket")
        val path = data["path"]?.jsonPrimitive?.content ?: "/socket/screen"

        val done = CompletableDeferred<Outcome>()
        val listener = Listener(ticket, done)
        val socket = client.openModuleSocket(path, listener) ?: return Outcome.Fatal("Dirección no válida")
        ws = socket
        val pinger = scope.launch {
            while (isActive) {
                delay(2000)
                socket.send("""{"t":"ping","ts":${System.nanoTime() / 1_000_000}}""")
            }
        }
        try {
            return done.await()
        } finally {
            pinger.cancel()
            if (ws === socket) ws = null
            socket.close(1000, null)
        }
    }

    private inner class Listener(val ticket: String, val done: CompletableDeferred<Outcome>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(buildJsonObject {
                put("ticket", ticket)
                put("quality", quality)
                display?.let { put("display", it) }
            }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (root["kind"]?.jsonPrimitive?.content) {
                "config" -> runCatching { json.decodeFromJsonElement<ScreenConfig>(root) }.getOrNull()?.let {
                    _config.value = it
                    display = it.display.index
                    _state.value = ScreenState.Streaming
                }
                "status" -> {
                    val st = root["state"]?.jsonPrimitive?.content
                    _state.value = if (st == "blocked")
                        ScreenState.Blocked(root["message"]?.jsonPrimitive?.content ?: "Pantalla protegida")
                    else if (_config.value != null) ScreenState.Streaming else ScreenState.Connecting
                }
                "stats" -> _stats.value = ScreenStats(
                    fps = root["fps"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    kbps = root["kbps"]?.jsonPrimitive?.intOrNull ?: 0,
                    bitrateKbps = root["bitrateKbps"]?.jsonPrimitive?.intOrNull ?: 0,
                    encodeMs = root["encodeMs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    rttMs = rttMs,
                )
                "pong" -> root["ts"]?.jsonPrimitive?.longOrNull?.let { rttMs = System.nanoTime() / 1_000_000 - it }
                "error" -> done.complete(Outcome.Fatal(root["message"]?.jsonPrimitive?.content ?: "Error en el PC"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val buf = bytes.asByteBuffer()
            if (buf.remaining() < 1) return
            when (buf.get(0).toInt()) {
                0x01 -> if (buf.remaining() > 10) {
                    val key = (buf.get(1).toInt() and 1) == 1
                    val ts = buf.getLong(2)
                    buf.position(10)
                    sink?.onFrame(buf.slice(), key, ts)
                }
                0x02 -> if (buf.remaining() >= 6) {
                    _cursor.value = RemoteCursor(buf.getShort(2).toInt(), buf.getShort(4).toInt(), buf.get(1).toInt() != 0)
                }
                0x03 -> if (buf.remaining() >= 10) {
                    val w = buf.getShort(2).toInt() and 0xFFFF
                    val h = buf.getShort(4).toInt() and 0xFFFF
                    val hx = buf.getShort(6).toInt() and 0xFFFF
                    val hy = buf.getShort(8).toInt() and 0xFFFF
                    if (w in 1..256 && h in 1..256 && buf.remaining() >= 10 + w * h * 4) {
                        val pixels = IntArray(w * h)
                        for (i in pixels.indices) {
                            val o = 10 + i * 4
                            val r = buf.get(o).toInt() and 0xFF
                            val g = buf.get(o + 1).toInt() and 0xFF
                            val b = buf.get(o + 2).toInt() and 0xFF
                            val a = buf.get(o + 3).toInt() and 0xFF
                            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        _cursorImage.value = CursorImage(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), hx, hy)
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            done.complete(when (code) {
                AgentClient.CLOSE_REVOKED -> Outcome.Fatal("Este móvil ya no está autorizado en el PC.")
                1008 -> Outcome.Retry(reason.ifBlank { "Rechazado" })
                else -> if (reason == "Stopped on the PC") Outcome.Fatal("Se dejó de compartir la pantalla desde el PC.")
                        else Outcome.Retry("cerrado ($code)")
            })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            done.complete(Outcome.Retry(t.message ?: "Fallo de red"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.complete(Outcome.Retry("cerrado ($code)"))
        }
    }

    // ── Input ──────────────────────────────────────────

    private fun send(obj: JsonObject) { ws?.send(obj.toString()) }

    /** Cursor to a point of the monitor, both coordinates 0..1. */
    fun move(nx: Float, ny: Float) = send(buildJsonObject {
        put("t", "mv"); put("x", nx.coerceIn(0f, 1f)); put("y", ny.coerceIn(0f, 1f))
    })

    fun button(button: String, down: Boolean) = send(buildJsonObject { put("t", "btn"); put("b", button); put("d", down) })

    fun click(button: String = "left") { button(button, true); button(button, false) }

    /** Wheel in raw units (120 = one notch). Positive dy scrolls up. */
    fun wheel(dy: Int, dx: Int = 0) = send(buildJsonObject { put("t", "wheel"); put("dy", dy); put("dx", dx) })

    fun keys(combo: String) = send(buildJsonObject { put("t", "keys"); put("k", combo) })

    fun key(key: String, down: Boolean) = send(buildJsonObject { put("t", "key"); put("k", key); put("d", down) })

    fun text(s: String) { if (s.isNotEmpty()) send(buildJsonObject { put("t", "text"); put("s", s) }) }

    fun requestKeyFrame() = send(buildJsonObject { put("t", "kf") })

    fun setQuality(q: String) {
        quality = q
        send(buildJsonObject { put("t", "quality"); put("q", q) })
    }

    fun setDisplay(index: Int) {
        display = index
        send(buildJsonObject { put("t", "display"); put("i", index) })
    }

    companion object { private const val TAG = "ScreenStream" }
}
