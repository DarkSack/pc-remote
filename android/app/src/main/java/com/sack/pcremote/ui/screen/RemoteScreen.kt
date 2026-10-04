package com.sack.pcremote.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.net.ScreenConfig
import com.sack.pcremote.net.ScreenState
import com.sack.pcremote.net.ScreenStream
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════
// Pantalla remota, al estilo de RustDesk.
//
// Modo ratón (por defecto): el dedo mueve el cursor como un touchpad
//   sobre la imagen; toque = clic, 2 dedos toque = clic derecho,
//   mantener = arrastrar, 2 dedos arrastrar = scroll, pellizcar = zoom.
// Modo táctil: se toca donde se quiere hacer clic; mantener = clic
//   derecho, mantener y mover = arrastrar, 1 dedo = desplazar la vista
//   con zoom o hacer scroll sin él.
//
// Arriba, una barra (plegable) con teclado, teclas especiales, modo,
// pantalla, calidad y estadísticas. El vídeo se pausa al salir de la
// app: nadie mira la pantalla sin que el móvil esté delante.
// ══════════════════════════════════════════════════════════════

class ScreenViewModel(client: AgentClient) : ViewModel() {
    val stream = ScreenStream(client)
    override fun onCleared() = stream.close()
}

private const val LONG_PRESS_MS = 450L
private const val SCROLL_UNITS_PER_PX = 2.5f
private const val MAX_ZOOM = 6f

private enum class InputMode { Mouse, Touch }

private val QUALITIES = listOf("speed" to "Velocidad", "balanced" to "Equilibrada", "quality" to "Calidad")

/** Fit, zoom and pan of the PC image inside the phone screen. */
@Stable
private class Viewport {
    var cw by mutableFloatStateOf(0f)
    var ch by mutableFloatStateOf(0f)
    var sw by mutableIntStateOf(16)
    var sh by mutableIntStateOf(9)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    val fit get() = if (cw == 0f || ch == 0f) 1f else min(cw / sw, ch / sh)
    val bw get() = sw * fit
    val bh get() = sh * fit
    val bl get() = (cw - bw) / 2
    val bt get() = (ch - bh) / 2

    /** Phone pixel → point of the PC monitor (0..1). */
    fun toNormalized(p: Offset) = Offset(((p.x - panX) / zoom - bl) / bw, ((p.y - panY) / zoom - bt) / bh)

    fun toScreen(nx: Float, ny: Float) = Offset(panX + zoom * (bl + nx * bw), panY + zoom * (bt + ny * bh))

    fun zoomBy(factor: Float, focal: Offset, pan: Offset) {
        val nz = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val f = nz / zoom
        panX = focal.x - (focal.x - panX) * f + pan.x
        panY = focal.y - (focal.y - panY) * f + pan.y
        zoom = nz
        clamp()
    }

    fun panBy(d: Offset) { panX += d.x; panY += d.y; clamp() }

    /** Small image: centred. Bigger than the screen: no empty border. */
    fun clamp() {
        val vw = zoom * bw
        val vh = zoom * bh
        panX = if (vw <= cw) cw / 2 - zoom * (bl + bw / 2) else panX.coerceIn(cw - zoom * (bl + bw), -zoom * bl)
        panY = if (vh <= ch) ch / 2 - zoom * (bt + bh / 2) else panY.coerceIn(ch - zoom * (bt + bh), -zoom * bt)
    }

    fun reset() { zoom = 1f; clamp() }

    /** While zoomed, slide the view so the cursor never leaves it. */
    fun follow(p: Offset, margin: Float) {
        if (zoom <= 1.01f) return
        if (p.x < margin) panX += margin - p.x
        if (p.x > cw - margin) panX -= p.x - (cw - margin)
        if (p.y < margin) panY += margin - p.y
        if (p.y > ch - margin) panY -= p.y - (ch - margin)
        clamp()
    }
}

/** Where the PC cursor is, as far as the phone knows (0..1 of the monitor). */
private class CursorTracker {
    var x by mutableFloatStateOf(0.5f)
    var y by mutableFloatStateOf(0.5f)
    var sentX = -1f
    var sentY = -1f
    /** Uptime of the last move made on the phone: the PC's own reports wait until it settles. */
    var localAt = 0L
    var scrollY = 0f
    var scrollX = 0f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: PcSession, onBack: () -> Unit) {
    val client = vm.client ?: return
    val model: ScreenViewModel = viewModel { ScreenViewModel(client) }
    val stream = model.stream

    val state by stream.state.collectAsStateWithLifecycle()
    val config by stream.config.collectAsStateWithLifecycle()
    val stats by stream.stats.collectAsStateWithLifecycle()
    val remoteCursor by stream.cursor.collectAsStateWithLifecycle()
    val cursorImage by stream.cursorImage.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(InputMode.Mouse) }
    var quality by rememberSaveable { mutableStateOf("balanced") }
    var toolbar by rememberSaveable { mutableStateOf(true) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    val mods = remember { mutableStateListOf<String>() }

    val viewport = remember { Viewport() }
    val cursor = remember { CursorTracker() }
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop

    // Streaming follows the app: paused in the background, back when it returns.
    LifecycleStartEffect(stream) {
        stream.start(quality)
        onStopOrDispose { stream.stop() }
    }
    FullScreen()

    LaunchedEffect(config?.sourceWidth, config?.sourceHeight) {
        config?.let { viewport.sw = it.sourceWidth; viewport.sh = it.sourceHeight; viewport.reset() }
    }

    // The PC's cursor wins unless the phone moved it a moment ago.
    LaunchedEffect(remoteCursor, config) {
        val c = config ?: return@LaunchedEffect
        if (SystemClock.uptimeMillis() - cursor.localAt > 400) {
            cursor.x = remoteCursor.x / c.sourceWidth.toFloat()
            cursor.y = remoteCursor.y / c.sourceHeight.toFloat()
        }
    }

    fun flushMove() {
        if (cursor.x != cursor.sentX || cursor.y != cursor.sentY) {
            cursor.sentX = cursor.x; cursor.sentY = cursor.y
            stream.move(cursor.x, cursor.y)
        }
    }

    // Moves and scroll go out at most every 8 ms, coalesced.
    LaunchedEffect(stream) {
        while (isActive) {
            delay(8)
            flushMove()
            val sy = cursor.scrollY.toInt()
            val sx = cursor.scrollX.toInt()
            if (sy != 0 || sx != 0) {
                cursor.scrollY -= sy; cursor.scrollX -= sx
                stream.wheel(sy, sx)
            }
        }
    }

    /** Held modifiers (Ctrl, Alt…) apply to the next click or key, then release. */
    fun withMods(action: () -> Unit) {
        val held = mods.toList()
        held.forEach { stream.key(it, true) }
        action()
        held.asReversed().forEach { stream.key(it, false) }
        mods.clear()
    }

    fun click(button: String) {
        flushMove()
        withMods { stream.click(button) }
    }

    fun pressKey(key: String) {
        haptics.tick()
        stream.keys((mods + key).joinToString("+"))
        mods.clear()
    }

    fun setLocal(n: Offset) {
        cursor.x = n.x.coerceIn(0f, 1f)
        cursor.y = n.y.coerceIn(0f, 1f)
        cursor.localAt = SystemClock.uptimeMillis()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport.cw = it.width.toFloat(); viewport.ch = it.height.toFloat(); viewport.clamp() }
            .pointerInput(mode, slop) {
                val margin = 48.dp.toPx()
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startedAt = first.uptimeMillis
                    val startPos = first.position
                    var maxPointers = 1
                    var travelled = 0f
                    var longPressed = false
                    var dragging = false
                    var twoFinger = 0 // 0 undecided, 1 scroll, 2 zoom
                    var startSpread = 0f
                    var lastSpread = 0f
                    var lastPos = startPos

                    while (true) {
                        val event = if (!longPressed && maxPointers == 1 && travelled < slop) {
                            val remaining = startedAt + LONG_PRESS_MS - SystemClock.uptimeMillis()
                            try {
                                withTimeout(max(1L, remaining)) { awaitPointerEvent() }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                longPressed = true
                                haptics.longPress()
                                if (mode == InputMode.Mouse) {
                                    flushMove()
                                    stream.button("left", true)
                                    dragging = true
                                } else {
                                    setLocal(viewport.toNormalized(startPos))
                                    flushMove()
                                }
                                continue
                            }
                        } else awaitPointerEvent()

                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > maxPointers) {
                            maxPointers = pressed.size
                            if (pressed.size == 2) { startSpread = spread(pressed); lastSpread = startSpread }
                        }
                        val move = pressed.fold(Offset.Zero) { a, c -> a + c.positionChange() } / pressed.size.toFloat()
                        travelled += move.getDistance()

                        if (pressed.size == 1 && maxPointers == 1) {
                            val pos = pressed[0].position
                            lastPos = pos
                            when (mode) {
                                InputMode.Mouse -> if (travelled >= slop || longPressed) {
                                    // 1:1 with the finger over the visible image, slightly faster when flicked.
                                    val gain = 1f + min(move.getDistance(), 40f) / 60f
                                    setLocal(Offset(
                                        cursor.x + move.x * gain / (viewport.zoom * viewport.bw),
                                        cursor.y + move.y * gain / (viewport.zoom * viewport.bh),
                                    ))
                                    viewport.follow(viewport.toScreen(cursor.x, cursor.y), margin)
                                }
                                InputMode.Touch -> when {
                                    longPressed -> {
                                        if (!dragging && travelled >= slop) {
                                            flushMove()
                                            stream.button("left", true)
                                            dragging = true
                                        }
                                        if (dragging) {
                                            setLocal(viewport.toNormalized(pos))
                                            viewport.follow(pos, margin)
                                        }
                                    }
                                    travelled >= slop -> if (viewport.zoom > 1.01f) viewport.panBy(move)
                                        else cursor.scrollY += move.y * SCROLL_UNITS_PER_PX
                                }
                            }
                        } else if (pressed.size >= 2) {
                            val spread = spread(pressed)
                            val centroid = pressed.fold(Offset.Zero) { a, c -> a + c.position } / pressed.size.toFloat()
                            if (twoFinger == 0) {
                                twoFinger = when {
                                    abs(spread - startSpread) > slop * 1.5f -> 2
                                    travelled > slop * 1.5f -> 1
                                    else -> 0
                                }
                            }
                            when (twoFinger) {
                                2 -> if (lastSpread > 0f) viewport.zoomBy(spread / lastSpread, centroid, move)
                                1 -> {
                                    cursor.scrollY += move.y * SCROLL_UNITS_PER_PX
                                    cursor.scrollX -= move.x * SCROLL_UNITS_PER_PX
                                }
                            }
                            lastSpread = spread
                        }
                        event.changes.forEach { it.consume() }
                    }

                    when {
                        dragging -> { flushMove(); stream.button("left", false); mods.clear() }
                        longPressed && mode == InputMode.Touch -> click("right")
                        travelled < slop * 1.5f && twoFinger == 0 -> when (maxPointers) {
                            1 -> {
                                if (mode == InputMode.Touch) setLocal(viewport.toNormalized(lastPos))
                                click("left")
                            }
                            2 -> click("right")
                            else -> click("middle")
                        }
                    }
                }
            },
    ) {
        // Video and cursor share the zoom/pan transform.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = viewport.zoom; scaleY = viewport.zoom
                    translationX = viewport.panX; translationY = viewport.panY
                },
        ) {
            val c = config
            if (c != null) {
                VideoSurface(
                    stream = stream,
                    config = c,
                    onFirstFrame = { firstFrame = true },
                    modifier = Modifier
                        .absoluteOffset { IntOffset(viewport.bl.roundToInt(), viewport.bt.roundToInt()) }
                        .size(with(density) { viewport.bw.toDp() }, with(density) { viewport.bh.toDp() }),
                )
            }
        }

        // Cursor on top, at a size that stays visible when the image is shrunk.
        val img = cursorImage
        if (config != null && img != null && (remoteCursor.visible || mode == InputMode.Mouse)) {
            Canvas(Modifier.fillMaxSize()) {
                val p = viewport.toScreen(cursor.x, cursor.y)
                val scale = max(viewport.zoom * viewport.fit, density.density * 0.5f)
                val bmp = img.bitmap.asImageBitmap()
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset((p.x - img.hotX * scale).roundToInt(), (p.y - img.hotY * scale).roundToInt()),
                    dstSize = IntSize((bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt()),
                )
            }
        }

        StatusOverlay(state, config, firstFrame, onRetry = { stream.start(quality) }, onBack = onBack)

        if (showStats) StatsOverlay(stats, config, Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(8.dp))

        Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 4.dp)) {
            Toolbar(
                expanded = toolbar,
                onToggle = { toolbar = !toolbar },
                mode = mode,
                onMode = { haptics.tick(); mode = if (mode == InputMode.Mouse) InputMode.Touch else InputMode.Mouse },
                keyboard = keyboard,
                onKeyboard = { keyboard = !keyboard },
                showKeys = showKeys,
                onKeys = { showKeys = !showKeys },
                zoomed = viewport.zoom > 1.01f,
                onFit = { viewport.reset() },
                config = config,
                onDisplay = { stream.setDisplay(it); firstFrame = false },
                quality = quality,
                onQuality = { quality = it; stream.setQuality(it); firstFrame = false },
                showStats = showStats,
                onStats = { showStats = !showStats },
                onBack = onBack,
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding()) {
            if (showKeys) SpecialKeys(mods = mods, onKey = ::pressKey, onToggleMod = {
                haptics.tick()
                if (it in mods) mods.remove(it) else mods.add(it)
            })
        }

        HiddenKeyboard(
            visible = keyboard,
            onHidden = { keyboard = false },
            onText = { s ->
                if (mods.isNotEmpty() && s.length == 1 && !s[0].isWhitespace()) pressKey(s.lowercase())
                else withMods { stream.text(s) }
            },
            onBackspace = { n -> repeat(n) { stream.keys("backspace") } },
        )
    }
}

private fun spread(pressed: List<PointerInputChange>): Float =
    if (pressed.size < 2) 0f else (pressed[0].position - pressed[1].position).getDistance()

// ── Video ─────────────────────────────────────────────

@Composable
private fun VideoSurface(stream: ScreenStream, config: ScreenConfig, onFirstFrame: () -> Unit, modifier: Modifier) {
    val holder = remember { DecoderHolder(stream) }
    DisposableEffect(holder) { onDispose { holder.detach() } }
    // Every config means a new encoder on the PC (other monitor, other quality):
    // its parameter sets may differ, so the decoder starts over too.
    LaunchedEffect(config) {
        holder.onFirstFrame = onFirstFrame
        holder.setSize(config.width, config.height)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = holder.attach(Surface(st))
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { holder.detach(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

/** Builds a decoder once there is both a surface and a video size; rebuilds it when either changes. */
private class DecoderHolder(private val stream: ScreenStream) {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var decoder: VideoDecoder? = null
    var onFirstFrame: (() -> Unit)? = null

    fun attach(s: Surface) { surface = s; rebuild() }

    fun setSize(w: Int, h: Int) {
        width = w; height = h
        rebuild()
    }

    fun detach() {
        stream.sink = null
        decoder?.release()
        decoder = null
        surface?.release()
        surface = null
    }

    private fun rebuild() {
        stream.sink = null
        decoder?.release()
        decoder = null
        val s = surface ?: return
        if (width <= 0 || height <= 0) return
        val d = VideoDecoder(s, width, height) { stream.requestKeyFrame() }
        d.onFirstFrame = { onFirstFrame?.invoke() }
        decoder = d
        stream.sink = d
        stream.requestKeyFrame()
    }
}

// ── Overlays ──────────────────────────────────────────

@Composable
private fun StatusOverlay(state: ScreenState, config: ScreenConfig?, firstFrame: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val (title, body, busy, actions) = when (state) {
        ScreenState.Connecting -> Quad("Conectando con la pantalla…", null, true, false)
        ScreenState.Streaming -> if (firstFrame || config == null) return else Quad("Esperando la imagen…", null, true, false)
        is ScreenState.Blocked -> Quad("Pantalla protegida", state.message, false, false)
        is ScreenState.Failed -> Quad("No se puede ver la pantalla", state.message, false, true)
        ScreenState.Stopped -> return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
            Column(Modifier.padding(24.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (busy) CircularProgressIndicator(Modifier.size(32.dp).padding(bottom = 4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (actions) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Volver") }
                    Button(onClick = onRetry) { Text("Reintentar") }
                }
            }
        }
    }
}

private data class Quad(val title: String, val body: String?, val busy: Boolean, val actions: Boolean)

@Composable
private fun StatsOverlay(stats: com.sack.pcremote.net.ScreenStats?, config: ScreenConfig?, modifier: Modifier) {
    val s = stats ?: return
    val c = config ?: return
    Surface(modifier, shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
        Text(
            buildString {
                append("${s.fps.roundToInt()} fps · ${"%.1f".format(s.kbps / 1000.0)} Mbps")
                s.rttMs?.let { append(" · ${it} ms") }
                append("\n${c.width}×${c.height} · ${c.encoder}${if (c.hardware) " (GPU)" else " (CPU)"} · ${"%.1f".format(s.encodeMs)} ms")
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Toolbar(
    expanded: Boolean, onToggle: () -> Unit,
    mode: InputMode, onMode: () -> Unit,
    keyboard: Boolean, onKeyboard: () -> Unit,
    showKeys: Boolean, onKeys: () -> Unit,
    zoomed: Boolean, onFit: () -> Unit,
    config: ScreenConfig?, onDisplay: (Int) -> Unit,
    quality: String, onQuality: (String) -> Unit,
    showStats: Boolean, onStats: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        tonalElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (expanded) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Salir") }
                Toggle(keyboard, onKeyboard, Icons.Outlined.Keyboard, "Teclado")
                Toggle(showKeys, onKeys, Icons.Outlined.KeyboardCommandKey, "Teclas especiales")
                IconButton(onClick = onMode) {
                    Icon(if (mode == InputMode.Mouse) Icons.Outlined.Mouse else Icons.Outlined.TouchApp,
                        if (mode == InputMode.Mouse) "Modo ratón (cambiar a táctil)" else "Modo táctil (cambiar a ratón)")
                }
                val displays = config?.displays.orEmpty()
                if (displays.size > 1) {
                    Menu(Icons.Outlined.Monitor, "Pantalla", displays.map { it.index.toString() to it.name }, config?.display?.index?.toString()) { onDisplay(it.toInt()) }
                }
                Menu(Icons.Outlined.HighQuality, "Calidad", QUALITIES, quality, onQuality)
                if (zoomed) IconButton(onClick = onFit) { Icon(Icons.Outlined.FitScreen, "Ajustar a la pantalla") }
                Toggle(showStats, onStats, Icons.Outlined.QueryStats, "Estadísticas")
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Ocultar barra" else "Mostrar barra")
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    IconToggleButton(checked = on, onCheckedChange = { onClick() }) { Icon(icon, label) }
}

@Composable
private fun Menu(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(icon, label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (key == selected) Icon(Icons.Outlined.Check, null) },
                    onClick = { open = false; if (key != selected) onSelect(key) },
                )
            }
        }
    }
}

private data class SpecialKey(val label: String, val key: String)

private val MODIFIERS = listOf(SpecialKey("Ctrl", "ctrl"), SpecialKey("Alt", "alt"), SpecialKey("Mayús", "shift"), SpecialKey("Win", "win"))

private val KEYS = listOf(
    SpecialKey("Esc", "esc"), SpecialKey("Tab", "tab"), SpecialKey("⌫", "backspace"), SpecialKey("Supr", "delete"),
    SpecialKey("Enter", "enter"), SpecialKey("←", "left"), SpecialKey("↑", "up"), SpecialKey("↓", "down"), SpecialKey("→", "right"),
    SpecialKey("Inicio", "home"), SpecialKey("Fin", "end"), SpecialKey("RePág", "pageup"), SpecialKey("AvPág", "pagedown"),
    SpecialKey("Alt+Tab", "alt+tab"), SpecialKey("Escritorio", "win+d"), SpecialKey("Copiar", "ctrl+c"), SpecialKey("Pegar", "ctrl+v"),
    SpecialKey("Deshacer", "ctrl+z"), SpecialKey("Tareas", "ctrl+shift+esc"), SpecialKey("ImpPt", "printscreen"),
) + (1..12).map { SpecialKey("F$it", "f$it") }

@Composable
private fun SpecialKeys(mods: List<String>, onKey: (String) -> Unit, onToggleMod: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MODIFIERS.forEach { m ->
                FilterChip(selected = m.key in mods, onClick = { onToggleMod(m.key) }, label = { Text(m.label) })
            }
            KEYS.forEach { k ->
                AssistChip(onClick = { onKey(k.key) }, label = { Text(k.label) })
            }
        }
    }
}

// ── Keyboard ──────────────────────────────────────────

/** Two characters always before the caret, so Backspace has something to delete even when "empty". */
private const val SENTINEL = "  "

/**
 * An invisible text field that owns the soft keyboard. What the keyboard does to
 * it is diffed against the previous value and replayed on the PC: inserted text
 * as Unicode, removed characters as Backspace. That also covers autocorrect
 * replacing a whole word.
 */
@Composable
private fun HiddenKeyboard(visible: Boolean, onHidden: () -> Unit, onText: (String) -> Unit, onBackspace: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    var wasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) { focus.requestFocus(); keyboard?.show() } else { keyboard?.hide() }
    }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            val old = value.text
            val new = next.text
            var prefix = 0
            while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
            val removed = old.length - prefix
            val added = new.substring(prefix)
            if (removed > 0) onBackspace(removed)
            if (added.isNotEmpty()) onText(added)
            // Back to the sentinel once nothing is being composed, so the field never grows.
            value = if (next.composition == null && (new.length < SENTINEL.length || new.length > 64 || added.contains('\n')))
                TextFieldValue(SENTINEL, TextRange(SENTINEL.length)) else next
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.None,
        ),
        modifier = Modifier
            .size(1.dp)
            .graphicsLayer { alpha = 0f }
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) onHidden()
                wasFocused = state.isFocused
            },
    )
}

// ── Window ────────────────────────────────────────────

/** Immersive (system bars hidden, swipe to peek) and screen kept on while watching. */
@Composable
private fun FullScreen() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
