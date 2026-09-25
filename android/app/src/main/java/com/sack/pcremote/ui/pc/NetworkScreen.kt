package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.extendedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Red del PC: tráfico en vivo, interfaces (IP, puerta de enlace, DNS),
// conexiones TCP y un ping lanzado desde el propio PC.
// ══════════════════════════════════════════════════════════════

@Composable
fun NetworkScreen(vm: PcSession, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val version by vm.history.version.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<NetworkInfo?>(null) }
    var error by remember { mutableStateOf<AgentError?>(null) }
    var host by rememberSaveable { mutableStateOf("") }
    var pinging by remember { mutableStateOf(false) }
    var pings by remember { mutableStateOf<List<PingResult>>(emptyList()) }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        while (true) {
            runCatching { vm.call("network", "info", serializer = NetworkInfo.serializer()) }
                .onSuccess { info = it; error = null }
                .onFailure { if (info == null) error = AgentError.from(it) }
            delay(10_000)
        }
    }

    fun ping() {
        haptics.tick()
        pinging = true
        scope.launch {
            val params = buildJsonObject { if (host.isNotBlank()) put("host", host.trim()) }
            runCatching { vm.call("network", "ping", params, PingResult.serializer(), timeoutMs = 15_000) }
                .onSuccess { pings = (listOf(it) + pings).take(5); haptics.confirm() }
                .onFailure { haptics.reject(); vm.post("Ping: ${friendlyMessage(it)}") }
            pinging = false
        }
    }

    SubScreen("Red", back, subtitle = info?.lanIp) { padding ->
        Box(Modifier.padding(padding)) {
            RequireConnection(vm) {
                val i = info
                when {
                    i == null && error != null -> ErrorState(error!!)
                    i == null -> SkeletonList(6)
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            val down = remember(version) { vm.history.down.toList() }
                            NetworkStatusCard(stats?.net, latency, i.lanIp, downHistory = down, capacity = vm.history.capacity)
                        }

                        item { SectionHeader("Ping desde el PC") }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = host, onValueChange = { host = it.take(253) },
                                            label = { Text("Destino") },
                                            placeholder = { Text("Puerta de enlace") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                            keyboardActions = KeyboardActions(onGo = { if (!pinging) ping() }),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        FilledTonalButton(onClick = ::ping, enabled = !pinging, modifier = Modifier.height(56.dp)) {
                                            if (pinging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ping")
                                        }
                                    }
                                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("1.1.1.1", "8.8.8.8", "google.com").forEach { h ->
                                            SuggestionChip(onClick = { host = h }, label = { Text(h) })
                                        }
                                    }
                                    pings.forEach { r -> PingRow(r) }
                                }
                            }
                        }

                        item { SectionHeader("Interfaces") }
                        items(i.interfaces.sortedWith(compareByDescending<NetInterface> { it.primary }.thenByDescending { it.up }), key = { it.name }) { n ->
                            InterfaceCard(n)
                        }

                        item { SectionHeader("Conexiones TCP") }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Row(Modifier.padding(16.dp)) {
                                    Figure("Establecidas", i.tcp.established, Modifier.weight(1f))
                                    Figure("Escuchando", i.tcp.listening, Modifier.weight(1f))
                                    Figure("En espera", i.tcp.timeWait, Modifier.weight(1f))
                                }
                            }
                        }
                        if (i.remote.isNotEmpty()) {
                            item { SectionHeader("Con quién habla el PC") }
                            items(i.remote.take(25), key = { "r-" + it.address }) { r ->
                                ListItem(
                                    headlineContent = { Text(r.address, style = MonoStyle) },
                                    supportingContent = {
                                        Text("${r.count} conexión${if (r.count == 1) "" else "es"} · puertos ${r.ports.take(6).joinToString(", ")}",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    trailingContent = { if (r.local) Pill("LAN") },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PingRow(r: PingResult) {
    val ok = r.lost < r.sent
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (ok) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.error, live = false, size = 5.dp)
        Spacer(Modifier.width(6.dp))
        Text(r.host, style = MonoStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (ok) "${r.avgMs?.let { String.format(java.util.Locale.ROOT, "%.0f", it) } ?: "—"} ms · ${r.lost}/${r.sent} perdidos"
            else "Sin respuesta",
            style = MaterialTheme.typography.labelLarge,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

private fun iconFor(type: String): ImageVector = when (type.lowercase()) {
    "wifi", "wireless" -> Icons.Outlined.Wifi
    "ethernet" -> Icons.Outlined.SettingsEthernet
    "loopback" -> Icons.Outlined.Loop
    "vpn", "tunnel", "ppp" -> Icons.Outlined.VpnLock
    else -> Icons.Outlined.Lan
}

@Composable
private fun InterfaceCard(n: NetInterface) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconFor(n.type), size = 36.dp,
                    container = if (n.up) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = if (n.up) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    n.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (n.primary) Pill("Principal", color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                else Pill(if (n.up) "Activa" else "Inactiva")
            }
            if (n.up) {
                Spacer(Modifier.height(10.dp))
                n.ipv4?.let { Kv("IPv4", it + (n.prefixLength?.let { p -> "/$p" } ?: "")) }
                n.gateway?.let { Kv("Puerta de enlace", it) }
                if (n.dns.isNotEmpty()) Kv("DNS", n.dns.joinToString(", "))
                n.ipv6?.let { Kv("IPv6", it) }
                n.mac?.let { Kv("MAC", it) }
                n.speedMbps?.takeIf { it > 0 }?.let { Kv("Velocidad", if (it >= 1000) "${it / 1000} Gb/s" else "$it Mb/s") }
            }
        }
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(v, style = MonoStyle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Figure(label: String, value: Int, modifier: Modifier) {
    Column(modifier) {
        Text("$value", style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
