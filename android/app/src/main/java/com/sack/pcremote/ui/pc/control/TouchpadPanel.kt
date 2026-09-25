package com.sack.pcremote.ui.pc.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.ui.components.haptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════
// Touchpad.
//
//   1 dedo, arrastrar   → mover el cursor (con aceleración)
//   1 dedo, toque       → clic izquierdo
//   2 dedos, toque      → clic derecho
//   3 dedos, toque      → clic central
//   2 dedos, arrastrar  → scroll (vertical y horizontal)
//   mantener pulsado    → arrastrar (mouseDown … mouseUp al soltar)
//   franja derecha      → scroll con un solo dedo (uso con una mano)
//
// Los movimientos no se mandan por cada evento táctil (hasta 120 Hz en
// algunas pantallas): se acumulan y un bucle los envía cada ~16 ms, con la
// parte fraccionaria guardada para que los movimientos lentos no se pierdan
// por redondeo. Se envían con AgentClient.send (sin esperar respuesta).
// ══════════════════════════════════════════════════════════════

private const val LONG_PRESS_MS = 450L
private const val FLUSH_MS = 16L
/** Wheel units (120 = one notch) per pixel of two-finger drag. */
private const val SCROLL_UNITS_PER_PX = 2.5f

private class PointerAccumulator {
    var dx = 0f; var dy = 0f
    var scrollY = 0f; var scrollX = 0f
}

@Composable
fun TouchpadPanel(
    client: AgentClient,
    connected: Boolean,
    sensitivity: Float,
    onSensitivity: (Float) -> Unit,
) {
    val acc = remember { PointerAccumulator() }
    val haptics = haptics()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var showTuning by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    // Pending movement goes out before any button event. Otherwise a click or the
    // mouseUp ending a drag lands up to one flush interval behind the finger.
    fun flushMove() {
        synchronized(acc) {
            val mx = acc.dx.toInt(); val my = acc.dy.toInt()
            if (mx != 0 || my != 0) {
                acc.dx -= mx; acc.dy -= my
                client.send("input", "mouseMove", buildJsonObject { put("dx", mx); put("dy", my) })
            }
        }
    }

    // Flush loop: turns accumulated deltas into at most ~60 messages a second.
    LaunchedEffect(client) {
        while (isActive) {
            delay(FLUSH_MS)
            flushMove()
            val sy = synchronized(acc) { acc.scrollY.toInt().also { acc.scrollY -= it } }
            if (abs(sy) >= 1) client.send("input", "mouseScroll", buildJsonObject { put("delta", sy) })
            val sx = synchronized(acc) { acc.scrollX.toInt().also { acc.scrollX -= it } }
            if (abs(sx) >= 1) client.send("input", "mouseScroll", buildJsonObject { put("delta", sx); put("horizontal", true) })
        }
    }

    fun click(button: String) {
        flushMove()
        haptics.key()
        client.send("input", "mouseClick", buildJsonObject { put("button", button) })
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ── Touch surface ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(scheme.surfaceContainerLow, MaterialTheme.shapes.large)
                    .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.large)
                    .semantics { contentDescription = "Superficie táctil del ratón" }
                    .pointerInput(sensitivity, connected) {
                        if (!connected) return@pointerInput
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val startedAt = first.uptimeMillis
                            var maxPointers = 1
                            var travelled = 0f
                            var dragging = false

                            while (true) {
                                // Wake up at the long-press deadline so holding still starts a drag.
                                val event = if (!dragging && maxPointers == 1 && travelled < touchSlop) {
                                    val remaining = startedAt + LONG_PRESS_MS - android.os.SystemClock.uptimeMillis()
                                    try {
                                        withTimeout(maxOf(1L, remaining)) { awaitPointerEvent() }
                                    } catch (_: PointerEventTimeoutCancellationException) {
                                        dragging = true
                                        haptics.longPress()
                                        client.send("input", "mouseDown", buildJsonObject { put("button", "left") })
                                        continue
                                    }
                                } else {
                                    awaitPointerEvent()
                                }

                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                maxPointers = maxOf(maxPointers, pressed.size)

                                val move = pressed.fold(Offset.Zero) { a, c -> a + c.positionChange() } / pressed.size.toFloat()
                                travelled += sqrt(move.x * move.x + move.y * move.y)

                                if (pressed.size == 1 && (maxPointers == 1 || dragging)) {
                                    // Mild acceleration: fast flicks cover the screen, slow moves stay precise.
                                    val speed = sqrt(move.x * move.x + move.y * move.y)
                                    val gain = sensitivity * (1f + minOf(speed, 40f) / 25f)
                                    synchronized(acc) {
                                        acc.dx += move.x * gain
                                        acc.dy += move.y * gain
                                    }
                                } else if (pressed.size >= 2 && travelled > touchSlop) {
                                    // Natural scrolling, like the phone: fingers up → content up.
                                    synchronized(acc) {
                                        acc.scrollY += move.y * SCROLL_UNITS_PER_PX
                                        acc.scrollX -= move.x * SCROLL_UNITS_PER_PX
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }

                            when {
                                dragging -> {
                                    flushMove()
                                    client.send("input", "mouseUp", buildJsonObject { put("button", "left") })
                                }
                                travelled < touchSlop -> when (maxPointers) {
                                    1 -> click("left")
                                    2 -> click("right")
                                    else -> click("middle")
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                DotGrid()
                Text(
                    if (connected) "Arrastra para mover · toca para clic\n2 dedos: clic derecho y scroll\nMantén pulsado para arrastrar"
                    else "Sin conexión con el PC",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }

            // ── One-finger scroll strip, under the thumb ──
            Box(
                Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(scheme.surfaceContainer, MaterialTheme.shapes.large)
                    .semantics { contentDescription = "Franja de desplazamiento" }
                    .pointerInput(connected) {
                        if (!connected) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            haptics.tick()
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.pressed } ?: break
                                synchronized(acc) { acc.scrollY += c.positionChange().y * SCROLL_UNITS_PER_PX * 1.4f }
                                c.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.SwipeVertical, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }

        // ── Buttons, big and at the bottom: where the thumb is ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(64.dp)) {
            MouseButton("Clic izquierdo", "Izquierdo", Modifier.weight(2f), connected) { click("left") }
            MouseButton("Clic central", "Medio", Modifier.weight(1f), connected) { click("middle") }
            MouseButton("Clic derecho", "Derecho", Modifier.weight(2f), connected) { click("right") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensibilidad ${(sensitivity * 10).roundToInt() / 10f}×", style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { showTuning = !showTuning }) {
                Icon(Icons.Outlined.Tune, contentDescription = if (showTuning) "Ocultar sensibilidad" else "Ajustar sensibilidad")
            }
        }
        if (showTuning) {
            Slider(value = sensitivity, onValueChange = onSensitivity, valueRange = 0.5f..4f, steps = 34)
        }
    }
}

@Composable
private fun MouseButton(description: String, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.fillMaxHeight().semantics { contentDescription = description },
    ) { Text(label, maxLines = 1) }
}

/** A faint dot grid: tells the surface is a touch area without drawing a "HUD". */
@Composable
private fun DotGrid() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxSize()) {
        val step = 28.dp.toPx()
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) {
                drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
