package com.sack.pcremote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.ui.theme.MonoStyle

// Empty, error, loading and "plugin off" states. Every list and screen uses
// these, so no section is ever just a blank area.

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
             modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * An understandable error: a title, what to do, a retry button, and the
 * technical text behind "Ver detalles" for whoever needs it.
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "Reintentar",
    secondary: (@Composable () -> Unit)? = null,
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
             modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRetry != null) Button(onClick = onRetry) { Text(retryLabel) }
            secondary?.invoke()
        }
        if (detail != null) {
            TextButton(onClick = { showDetail = !showDetail }) {
                Text(if (showDetail) "Ocultar detalles" else "Ver detalles")
            }
            AnimatedVisibility(showDetail) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(detail, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A feature whose plugin is switched off in the PC's panel. */
@Composable
fun PluginOffState(name: String, modifier: Modifier = Modifier, onRefresh: (() -> Unit)? = null) {
    EmptyState(
        icon = Icons.Outlined.Extension,
        title = "$name está desactivado",
        message = "Actívalo en el panel del agente, en el PC (Plugins). Por seguridad no se puede activar desde el móvil.",
        modifier = modifier,
        action = onRefresh?.let { { OutlinedButton(onClick = it) { Text("Comprobar de nuevo") } } },
    )
}

/**
 * Skeleton placeholder: a surface that breathes slowly between two close
 * alphas. Deliberately calm — no sweeping shimmer.
 */
fun Modifier.skeleton(shape: Shape? = null): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    val s = shape ?: MaterialTheme.shapes.small
    this.graphicsLayer { this.alpha = alpha }.clip(s).background(MaterialTheme.colorScheme.surfaceContainerHighest)
}

@Composable
fun SkeletonLine(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(height).skeleton())
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier, height: Dp = 96.dp) {
    Box(modifier.fillMaxWidth().height(height).skeleton(MaterialTheme.shapes.medium))
}

/** A list of skeleton rows, for lists that are loading. */
@Composable
fun SkeletonList(rows: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).skeleton(CircleShape))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonLine(160.dp)
                    SkeletonLine(96.dp, 10.dp)
                }
            }
        }
    }
}
