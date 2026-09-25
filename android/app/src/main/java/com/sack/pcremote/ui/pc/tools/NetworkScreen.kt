package com.sack.pcremote.ui.pc.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.NumericStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Red: el enlace móvil ↔ PC (estado, latencia), el tráfico del PC en vivo,
// sus interfaces (IP, puerta de enlace, DNS), las conexiones TCP y un ping
// lanzado desde el propio PC.
// ══════════════════════════════════════════════════════════════

@Composable
fun NetworkScreen(session: PcSession, onBack: () -> Unit) {
    val client = session.client
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val state by session.state.collectAsState()
    val latency by session.latencyMs.collectAsState()
    val stats by session.stats.collectAsState()
    val history by session.history.collectAsState()
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val available = plugins.let { session.isAvailable("network") }

    var info by remember { mutableStateOf<NetworkInfo?>(null) }
    var connections by remember { mutableStateOf<List<TcpConnection>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var pingHost by remember { mutableStateOf("") }
    var ping by remember { mutableStateOf<PingResult?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var showConnections by remember { mutableStateOf(false) }

    LaunchedEffect(state, available) {
        if (state != ConnectionState.CONNECTED || !available) return@LaunchedEffect
        client.call("network", "info", NetworkInfo.serializer()).onSuccess { info = it; error = null }.onFailure { error = it }
    }
    LaunchedEffect(showConnections, state) {
        if (showConnections && state == ConnectionState.CONNECTED) {
            client.call("network", "connections", TcpConnections.serializer(), buildJsonObject { put("limit", 200) })
                .onSuccess { connections = it.connections }
        }
    }

    fun runPing() {
        scope.launch {
            pinging = true
            haptics.tick()
            client.call("network", "ping", PingResult.serializer(),
                buildJsonObject { if (pingHost.isNotBlank()) put("host", pingHost.trim()); put("count", 4) }, timeoutMs = 20_000)
                .onSuccess { ping = it; if (it.lossPct < 100) haptics.confirm() else haptics.reject() }
                .onFailure { haptics.reject(); ping = null; error = it }
            pinging = false
        }
    }

    PcScaffold(title = "Red", subtitle = creds.agentName, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetworkStatusCard(state, creds.agentHost, creds.agentPort, latency, stats?.net, history.rx, history.tx)

            if (!available) { PluginOffState("Red", onRefresh = session::refreshPlugins); return@Column }
            error?.let { ErrorState("No se pudo leer la red del PC", it.message ?: "", onRetry = { error = null; session.retry() }) }

            val i = info
            if (i == null && error == null) { SkeletonCard(height = 160.dp); SkeletonCard(height = 120.dp) }
            if (i != null) {
                SectionHeader("Interfaces del PC", subtitle = "${i.interfaces.count { it.up }} activas")
                i.interfaces.filter { it.up || it.primary }.forEach { InterfaceCard(it) }

                SectionHeader("Conexiones TCP")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat("Establecidas", i.tcp.established.toString(), Modifier.weight(1f))
                        Stat("Total", i.tcp.total.toString(), Modifier.weight(1f))
                        Stat("Escuchando", i.tcp.listeners.toString(), Modifier.weight(1f))
                    }
                    TextButton(onClick = { showConnections = !showConnections }, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        Text(if (showConnections) "Ocultar conexiones" else "Ver conexiones activas")
                    }
                    if (showConnections) {
                        val list = connections
                        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        else Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            list.take(100).forEach { c ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(c.remote, style = MonoStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(c.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            SectionHeader("Ping desde el PC", subtitle = "Vacío = su puerta de enlace")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    pingHost, { pingHost = it.trim() },
                    placeholder = { Text("1.1.1.1 o google.com") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::runPing, enabled = !pinging && state == ConnectionState.CONNECTED && available) {
                    if (pinging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ping")
                }
            }
            ping?.let { PingCard(it) }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** Phone ↔ PC link: state, address, latency and the PC's live throughput. */
@Composable
fun NetworkStatusCard(
    state: ConnectionState, host: String, port: Int, latency: Long?, net: NetStats?,
    rxHistory: List<Float>, txHistory: List<Float>,
) {
    val ext = PcRemoteTheme.extended
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatus(state, Modifier.weight(1f))
                Text(latency?.let { "$it ms" } ?: "—", style = MaterialTheme.typography.titleLarge.merge(NumericStyle))
            }
            Text("$host:$port", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("↓ Bajada", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(net?.let { formatBitrate(it.rxBps) } ?: "—", style = MaterialTheme.typography.titleMedium.merge(NumericStyle))
                    Sparkline(rxHistory, ext.net, max = null, height = 36.dp, description = "Historial de bajada")
                }
                Column(Modifier.weight(1f)) {
                    Text("↑ Subida", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(net?.let { formatBitrate(it.txBps) } ?: "—", style = MaterialTheme.typography.titleMedium.merge(NumericStyle))
                    Sparkline(txHistory, MaterialTheme.colorScheme.tertiary, max = null, height = 36.dp, description = "Historial de subida")
                }
            }
            net?.iface?.let {
                Spacer(Modifier.height(8.dp))
                Text(listOfNotNull(it, net.linkMbps?.let { m -> "enlace $m Mbps" }).joinToString(" · "),
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InterfaceCard(n: NetInterface) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when (n.type) { "wifi" -> Icons.Outlined.Wifi; "ethernet" -> Icons.Outlined.SettingsEthernet; else -> Icons.Outlined.Lan },
                     contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.name, style = MaterialTheme.typography.titleSmall)
                    Text(n.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.primary) AssistChip(onClick = {}, label = { Text("Principal") })
            }
            Spacer(Modifier.height(8.dp))
            n.ipv4.forEach { InfoRow("IPv4", it) }
            n.gateways.firstOrNull()?.let { InfoRow("Puerta de enlace", it) }
            if (n.dns.isNotEmpty()) InfoRow("DNS", n.dns.take(2).joinToString(", "))
            n.mac?.let { InfoRow("MAC", it) }
            n.speedMbps?.let { InfoRow("Velocidad", if (it >= 1000) "${it / 1000} Gbps" else "$it Mbps") }
        }
    }
}

@Composable
private fun PingCard(p: PingResult) {
    val ext = PcRemoteTheme.extended
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.host, style = MonoStyle, modifier = Modifier.weight(1f))
                Text(p.avgMs?.let { "%.0f ms".format(it) } ?: "sin respuesta",
                     style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                     color = if (p.avgMs != null) ext.success else MaterialTheme.colorScheme.error)
            }
            Text("Pérdida ${p.lossPct.toInt()}% · " + p.results.joinToString("  ") { it?.let { ms -> "$ms ms" } ?: "✕" },
                 style = MaterialTheme.typography.bodySmall.merge(NumericStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall.merge(NumericStyle))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
