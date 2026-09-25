package com.sack.pcremote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.AgentError
import com.sack.pcremote.ui.theme.MonoStyle

// ══════════════════════════════════════════════════════════════
// Estados de carga, vacío y error. Ninguna sección se queda en blanco.
// ══════════════════════════════════════════════════════════════

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, size = 64.dp,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Understandable error first; the exception text only behind "Ver detalles". */
@Composable
fun ErrorState(
    error: AgentError,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "Reintentar",
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, size = 64.dp,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.height(16.dp))
        Text(error.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text(retryLabel) }
        }
        extra?.invoke(this)
        if (error.technical != null) {
            TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalles" else "Ver detalles") }
            AnimatedVisibility(details) {
                SelectionContainer {
                    Text(
                        error.technical,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                    )
                }
            }
        }
    }
}

/** A calm shimmer: one slow sweep, low contrast. */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "x")
    background(Brush.linearGradient(
        colors = listOf(base, light, base),
        start = Offset(x * 600f - 300f, 0f),
        end = Offset(x * 600f + 300f, 0f),
    ))
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(modifier.height(height).clip(MaterialTheme.shapes.small).shimmer())
}

/** Placeholder rows for a list that is still loading. */
@Composable
fun SkeletonList(rows: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(40.dp), 40.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.6f), 14.dp)
                    SkeletonBox(Modifier.fillMaxWidth(0.35f), 10.dp)
                }
            }
        }
    }
}
