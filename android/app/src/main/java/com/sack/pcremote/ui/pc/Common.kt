package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.AgentError
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.ErrorState

// ══════════════════════════════════════════════════════════════
// Piezas comunes de las pantallas secundarias: la barra superior con
// "Atrás" y el aviso de "sin conexión" en lugar de una pantalla vacía.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    back: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás") } },
                actions = actions,
            )
        },
        floatingActionButton = floating,
        content = content,
    )
}

/**
 * Shows [content] while connected; otherwise a friendly "no connection"
 * state with Reintentar. Screens that need the PC wrap their body in it.
 */
@Composable
fun RequireConnection(vm: PcSession, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    when (state) {
        ConnectionState.CONNECTED -> content()
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.RECONNECTING ->
            Box(modifier.fillMaxSize().padding(48.dp)) {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Conectando con ${vm.displayName()}…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        else -> ErrorState(
            error ?: AgentError("Sin conexión", "No hay conexión con el PC."),
            modifier,
            icon = Icons.Outlined.CloudOff,
            onRetry = vm::retry,
        )
    }
}

/** True while the PC's feature [id] is known to be switched off in its panel. */
@Composable
fun featureDisabled(vm: PcSession, id: String): Boolean {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    return plugins?.features?.firstOrNull { it.id == id }?.enabled == false
}
