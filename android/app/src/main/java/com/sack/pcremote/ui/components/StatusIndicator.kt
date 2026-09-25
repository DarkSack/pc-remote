package com.sack.pcremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.ui.theme.PcRemoteTheme

/** What a connection state looks like: colour, label and whether it "breathes". */
data class StatusLook(val color: Color, val label: String, val live: Boolean)

@Composable
fun ConnectionState.look(): StatusLook {
    val ext = PcRemoteTheme.extended
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        ConnectionState.CONNECTED -> StatusLook(ext.success, "En línea", live = true)
        ConnectionState.CONNECTING -> StatusLook(ext.warning, "Conectando…", live = false)
        ConnectionState.AUTHENTICATING -> StatusLook(ext.warning, "Autenticando…", live = false)
        ConnectionState.RECONNECTING -> StatusLook(ext.warning, "Reconectando…", live = false)
        ConnectionState.FAILED -> StatusLook(scheme.error, "Sin conexión", live = false)
        ConnectionState.DISCONNECTED -> StatusLook(scheme.outline, "Desconectado", live = false)
    }
}

/**
 * A status dot. When [live], a faint ring grows out of it and fades every few
 * seconds — the only continuous animation in the app, and deliberately quiet.
 */
@Composable
fun StatusDot(color: Color, live: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val animated by animateColorAsState(color, tween(400), label = "dot")
    val t = if (live) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "ring",
        ).value
    } else 0f
    Canvas(modifier.size(size * 2)) {
        val r = size.toPx() / 2
        if (live) drawCircle(animated.copy(alpha = 0.28f * (1f - t)), radius = r * (1f + t))
        drawCircle(animated, radius = r)
    }
}

/**
 * Dot + text: state is never told by colour alone ("● En línea", not "●").
 */
@Composable
fun ConnectionStatus(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    label: String? = null,
    /** Overrides the text colour (the dot keeps the state colour), e.g. on inverse surfaces. */
    textColor: Color? = null,
) {
    val look = state.look()
    val text = label ?: look.label
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Estado: $text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(look.color, look.live, size = 8.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = style, color = textColor ?: look.color)
    }
}
