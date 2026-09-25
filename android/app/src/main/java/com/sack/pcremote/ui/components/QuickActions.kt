package com.sack.pcremote.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A square-ish action tile: icon over label. Destructive ones use the error
 * container so "Apagar" never looks like "Bloquear".
 */
@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Destructive = a quiet red tint and red content, not a saturated block: it
    // must stand out from "Bloquear" without shouting on a dark dashboard.
    val active = enabled && !busy
    val container = when {
        destructive && active -> scheme.error.copy(alpha = 0.14f)
        else -> scheme.surfaceContainerHigh.copy(alpha = if (active) 1f else 0.6f)
    }
    val content = if (destructive) scheme.error else scheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier.heightIn(min = 84.dp).semantics { role = Role.Button },
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp).alpha(if (enabled) 1f else 0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = content)
            else Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Plain Material confirmation for destructive actions (shutdown, kill, unpair…). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message) },
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
