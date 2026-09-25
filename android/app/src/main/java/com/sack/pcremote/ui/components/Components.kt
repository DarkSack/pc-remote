package com.sack.pcremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.ui.theme.extendedColors

// ══════════════════════════════════════════════════════════════
// Bloques pequeños que se repiten: cabeceras de sección, el punto de
// estado, barras de métrica, botones de acción rápida, diálogos.
// ══════════════════════════════════════════════════════════════

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { contentDescription = title },
        )
        action?.invoke()
    }
}

/** Colour and label of a connection state. Never colour alone: the label always goes with it. */
data class StatusLook(val color: Color, val label: String, val live: Boolean)

@Composable
fun ConnectionState.look(): StatusLook {
    val ext = MaterialTheme.extendedColors
    val cs = MaterialTheme.colorScheme
    return when (this) {
        ConnectionState.CONNECTED -> StatusLook(ext.success, "En línea", true)
        ConnectionState.CONNECTING -> StatusLook(ext.warning, "Conectando…", false)
        ConnectionState.AUTHENTICATING -> StatusLook(ext.warning, "Autenticando…", false)
        ConnectionState.RECONNECTING -> StatusLook(ext.warning, "Reconectando…", false)
        ConnectionState.FAILED -> StatusLook(cs.error, "Sin conexión", false)
        ConnectionState.DISCONNECTED -> StatusLook(cs.outline, "Desconectado", false)
    }
}

/**
 * The status dot. While online it breathes very slowly (a faint halo every
 * 2.4 s): enough to say "live", not enough to pull the eye from the data.
 */
@Composable
fun StatusDot(color: Color, live: Boolean, size: Dp = 10.dp) {
    val animated by animateColorAsState(color, tween(400), label = "dot")
    val halo = if (live) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "halo").value
    } else 0f
    Canvas(Modifier.size(size * 2)) {
        val r = size.toPx() / 2
        if (live) drawCircle(animated.copy(alpha = 0.35f * (1f - halo)), radius = r * (1f + halo))
        drawCircle(animated, radius = r)
    }
}

@Composable
fun ConnectionBadge(state: ConnectionState, modifier: Modifier = Modifier) {
    val look = state.look()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(look.color, look.live, 8.dp)
        Spacer(Modifier.width(4.dp))
        Text(look.label, style = MaterialTheme.typography.labelLarge, color = look.color)
    }
}

/** A labelled horizontal meter: "CPU ───── 32 %". */
@Composable
fun MetricBar(
    label: String,
    value: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    detail: String? = null,
) {
    val target = (fraction ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, tween(600), label = "bar")
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (fraction == null) 0f else animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = meterColor(target, color),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Past 85 % a meter turns amber, past 95 % red: the value says "look here". */
@Composable
fun meterColor(fraction: Float, normal: Color = MaterialTheme.colorScheme.primary): Color = when {
    fraction >= 0.95f -> MaterialTheme.colorScheme.error
    fraction >= 0.85f -> MaterialTheme.extendedColors.warning
    else -> normal
}

enum class Tone { Normal, Caution, Danger }

/** Square-ish tonal button for the quick actions grid. 72dp tall: comfortable one-handed. */
@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Normal,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.Normal -> cs.surfaceContainerHigh to cs.onSurface
        Tone.Caution -> MaterialTheme.extendedColors.warningContainer to MaterialTheme.extendedColors.onWarningContainer
        Tone.Danger -> cs.errorContainer to cs.onErrorContainer
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp).alpha(if (enabled) 1f else 0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A destructive action asks first (when the setting says so). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                         else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Small rounded label: "3 procesos", "integrada". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = modifier.clip(CircleShape).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

/** Icon inside a tinted rounded square: the visual anchor of list rows and cards. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(modifier.size(size).clip(MaterialTheme.shapes.small).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}
