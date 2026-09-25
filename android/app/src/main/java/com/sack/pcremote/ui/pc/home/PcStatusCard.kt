package com.sack.pcremote.ui.pc.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionProblem
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.SystemInfo
import com.sack.pcremote.net.SystemStats
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.NumericStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.delay

/**
 * The global status, first thing on the dashboard: is the PC up, which one, how
 * long it has been on, how fast it answers — and the three headline gauges.
 */
@Composable
fun PcStatusCard(
    name: String,
    state: ConnectionState,
    info: SystemInfo?,
    stats: SystemStats?,
    address: String,
    latencyMs: Long?,
    connectedSince: Long?,
    lastSync: Long?,
    problem: ConnectionProblem?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onWake: (() -> Unit)?,
) {
    val ext = PcRemoteTheme.extended
    // Relative times ("hace 3 s") need a clock that ticks.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val online = state == ConnectionState.CONNECTED

    ElevatedCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatus(state, Modifier.weight(1f), label = if (online) "PC en línea" else null)
                if (online && latencyMs != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("$latencyMs ms", style = MaterialTheme.typography.labelMedium.merge(NumericStyle)) },
                        leadingIcon = { Icon(Icons.Outlined.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.semantics { contentDescription = "Latencia $latencyMs milisegundos" },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(info?.hostname?.ifBlank { null } ?: name, style = MaterialTheme.typography.headlineMedium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                info?.let { "${it.os} · ${it.username}" } ?: "Windows",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact("Dirección", address, Modifier.weight(1.3f), mono = true)
                Fact("Encendido", (stats?.uptimeSec ?: info?.uptimeSec)?.let { formatDuration(it) } ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact("Conectado", connectedSince?.let { formatDuration((now - it) / 1000) } ?: "—", Modifier.weight(1.3f))
                Fact("Sincronizado", lastSync?.let { relativeTime(it, now) } ?: "—", Modifier.weight(1f))
            }

            AnimatedVisibility(online) {
                Column {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MetricGauge("CPU", stats?.cpu, ext.cpu)
                        MetricGauge("RAM", stats?.ramPct, ext.ram)
                        MetricGauge("GPU", stats?.gpu?.usage, ext.gpu)
                    }
                }
            }

            AnimatedVisibility(!online) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        problem?.let { "${it.title}. ${it.message}" } ?: "Esperando al PC…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Reintentar") }
                        if (onWake != null) OutlinedButton(onClick = onWake) {
                            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Encender")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium.merge(NumericStyle),
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
