package com.sack.pcremote.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.ui.theme.*
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
fun TouchpadPanel(client: AgentClient) {
    var sensitivity by rememberSaveable { mutableFloatStateOf(1.6f) }
    val acc = remember { PointerAccumulator() }
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // Pending movement goes out before any button event. Otherwise a click or the
    // mouseUp ending a drag lands up to one flush interval (16 ms of finger travel)
    // behind where the finger actually was.
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
            val sy = acc.scrollY.toInt()
            if (abs(sy) >= 1) {
                acc.scrollY -= sy
                client.send("input", "mouseScroll", buildJsonObject { put("delta", sy) })
            }
            val sx = acc.scrollX.toInt()
            if (abs(sx) >= 1) {
                acc.scrollX -= sx
                client.send("input", "mouseScroll", buildJsonObject { put("delta", sx); put("horizontal", true) })
            }
        }
    }

    fun click(button: String) {
        flushMove()
        client.send("input", "mouseClick", buildJsonObject { put("button", button) })
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CardDark, RoundedCornerShape(16.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                .pointerInput(sensitivity) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val startedAt = first.uptimeMillis
                        var maxPointers = 1
                        var travelled = 0f
                        var dragging = false

                        while (true) {
                            // Wait for the next event, but wake up at the long-press deadline
                            // so holding still starts a drag without needing movement.
                            val event = if (!dragging && maxPointers == 1 && travelled < touchSlop) {
                                // Pointer timestamps use SystemClock.uptimeMillis, so compare against the same clock.
                                val remaining = startedAt + LONG_PRESS_MS - android.os.SystemClock.uptimeMillis()
                                try {
                                    withTimeout(maxOf(1L, remaining)) { awaitPointerEvent() }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    dragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    client.send("input", "mouseDown", buildJsonObject { put("button", "left") })
                                    continue
                                }
                            } else {
                                awaitPointerEvent()
                            }

                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            maxPointers = maxOf(maxPointers, pressed.size)

                            // Average movement of the fingers still down.
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
            Text(
                "Arrastra para mover · toca para clic\n2 dedos: clic derecho y scroll · mantén pulsado para arrastrar",
                color = MutedDark, fontSize = 12.sp, lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MouseButton("Izquierdo", Modifier.weight(2f)) { click("left") }
            MouseButton("Medio", Modifier.weight(1f)) { click("middle") }
            MouseButton("Derecho", Modifier.weight(2f)) { click("right") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensibilidad", color = DimDark, fontSize = 12.sp, modifier = Modifier.width(90.dp))
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.5f..4f,
                modifier = Modifier.weight(1f),
            )
            Text("${(sensitivity * 10).roundToInt() / 10f}×", color = DimDark, fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }
    }
}

@Composable
private fun MouseButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CardDark, contentColor = TextDark),
    ) { Text(label, fontSize = 13.sp) }
}
