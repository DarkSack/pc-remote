package com.sack.pcremote.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.R
import com.sack.pcremote.net.AgentError
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.NetStats
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.delay

/** Recomposes every [periodMs] so "hace 5 s" / uptimes keep moving. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

/**
 * The first thing on Home: is the PC there? State with label, name, OS,
 * address, latency, time connected and last data received.
 */
@Composable
fun PcStatusCard(
    state: ConnectionState,
    name: String,
    os: String?,
    address: String,
    latencyMs: Long?,
    connectedSince: Long?,
    lastSync: Long?,
    error: AgentError?,
    searching: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onWake: (() -> Unit)? = null,
) {
    val look = state.look()
    val now = rememberNow()
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(look.color, look.live)
                Spacer(Modifier.width(6.dp))
                AnimatedContent(look.label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "state") {
                    Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.color)
                }
                Spacer(Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_logo_mark), null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Text(
                listOfNotNull(os, address).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            when (state) {
                ConnectionState.CONNECTED -> {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(Icons.Outlined.Speed, latencyMs?.let { "$it ms" } ?: "—", "Latencia", Modifier.weight(1f))
                        StatChip(Icons.Outlined.Timer, connectedSince?.let { formatDuration((now - it) / 1000) } ?: "—", "Conectado", Modifier.weight(1f))
                        StatChip(Icons.Outlined.Sync, lastSync?.let { formatAgo(it, now) } ?: "—", "Datos", Modifier.weight(1f))
                    }
                }
                ConnectionState.FAILED, ConnectionState.RECONNECTING, ConnectionState.DISCONNECTED -> {
                    if (error != null || searching) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (searching) "Buscando el PC en la red…" else error!!.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state == ConnectionState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!searching && error != null) Text(error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Reintentar") }
                        if (onWake != null) OutlinedButton(onClick = onWake) {
                            Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Encender")
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One metric: big value, a meter or a sparkline, and a detail line
 * ("4,3 GHz · 62 °C"). Tapping opens the detailed monitor.
 */
@Composable
fun SystemMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    detail: String? = null,
    history: List<Float>? = null,
    historyCapacity: Int = 0,
    historyMax: Float? = 100f,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            if (history != null) {
                Sparkline(history, Modifier.fillMaxWidth().height(36.dp), meterColor(fraction ?: 0f, color), historyMax, historyCapacity, description = "Historial de $label")
            } else if (fraction != null) {
                MetricBar("", "", fraction, color = color)
            }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = colors, content = content)
    else Card(modifier = modifier, colors = colors, content = content)
}

/** Download / upload / ping in one card. */
@Composable
fun NetworkStatusCard(
    net: NetStats?,
    latencyMs: Long?,
    lanIp: String?,
    modifier: Modifier = Modifier,
    downHistory: List<Float>? = null,
    capacity: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lan, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Red", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (lanIp != null) Text(lanIp, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                NetFigure(Icons.Outlined.ArrowDownward, "Bajada", net?.let { formatRate(it.downBps) } ?: "—", Modifier.weight(1f))
                NetFigure(Icons.Outlined.ArrowUpward, "Subida", net?.let { formatRate(it.upBps) } ?: "—", Modifier.weight(1f))
                NetFigure(Icons.Outlined.NetworkPing, "Ping", latencyMs?.let { "$it ms" } ?: "—", Modifier.weight(0.8f))
            }
            if (downHistory != null) {
                Spacer(Modifier.height(10.dp))
                Sparkline(downHistory, Modifier.fillMaxWidth().height(32.dp), MaterialTheme.colorScheme.secondary, null, capacity, description = "Historial de bajada")
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, content = body)
    else Card(modifier = modifier.fillMaxWidth(), colors = colors, content = body)
}

@Composable
private fun NetFigure(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
