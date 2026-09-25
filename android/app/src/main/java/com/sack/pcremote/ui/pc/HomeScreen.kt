package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.R
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.net.friendlyMessage
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════
// Inicio: la jerarquía de la app en una pantalla.
//   1. Estado global (¿está el PC?)       → PcStatusCard
//   2. Acciones rápidas                    → energía, silencio, atajos
//   3. Sistema                             → CPU, RAM, GPU, disco, red
//   4. Herramientas                        → pantallas secundarias
// Rejilla adaptativa: 2 columnas en teléfono, más en tablet.
// ══════════════════════════════════════════════════════════════

private data class PowerAction(
    val action: String, val label: String, val icon: ImageVector, val tone: Tone, val confirm: String?,
)

private val POWER = listOf(
    PowerAction("lock", "Bloquear", Icons.Outlined.Lock, Tone.Normal, null),
    PowerAction("sleep", "Suspender", Icons.Outlined.Bedtime, Tone.Normal, "El PC se suspenderá. Podrás despertarlo con Wake-on-LAN si está configurado."),
    PowerAction("restart", "Reiniciar", Icons.Outlined.RestartAlt, Tone.Caution, "Se cerrarán todas las aplicaciones abiertas en el PC."),
    PowerAction("shutdown", "Apagar", Icons.Outlined.PowerSettingsNew, Tone.Danger, "Se cerrarán todas las aplicaciones y el PC se apagará."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: PcSession, open: (String) -> Unit, onSwitchPc: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val since by vm.connectedSince.collectAsStateWithLifecycle()
    val creds by vm.creds.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val connected = state == ConnectionState.CONNECTED
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Haptic on the transitions that matter: connected, lost.
    var lastState by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED && lastState != ConnectionState.CONNECTED) haptics.confirm()
        if (state == ConnectionState.FAILED && lastState != ConnectionState.FAILED) haptics.reject()
        lastState = state
    }

    fun power(p: PowerAction) {
        scope.launch {
            runCatching { vm.run("system", p.action) }
                .onSuccess { haptics.confirm(); vm.post("${p.label}: enviado al PC") }
                .onFailure { haptics.reject(); vm.post("${p.label}: ${friendlyMessage(it)}") }
        }
    }

    val feature = { id: String -> plugins?.features?.firstOrNull { it.id == id } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_logo_mark), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Command Center", style = MaterialTheme.typography.titleMedium)
                            Text("PC Remote", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSwitchPc) { Icon(Icons.Outlined.Devices, contentDescription = "Cambiar de PC") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1 · Global status
            full {
                PcStatusCard(
                    state = state,
                    name = info?.hostname ?: creds?.agentName ?: "PC",
                    os = info?.os,
                    address = creds?.let { it.agentHost } ?: "",
                    latencyMs = latency,
                    connectedSince = since,
                    lastSync = stats?.ts?.takeIf { it > 0 },
                    error = error,
                    searching = searching,
                    onRetry = { haptics.tick(); vm.retry() },
                    onWake = creds?.macAddress?.takeIf { !connected }?.let { mac ->
                        {
                            haptics.confirm()
                            scope.launch {
                                runCatching { WakeOnLan.wake(mac, creds?.broadcast) }
                                    .onSuccess { vm.post("Señal de encendido enviada. El PC tarda unos segundos en arrancar.") }
                                    .onFailure { vm.post("No se pudo enviar: ${it.message}") }
                            }
                        }
                    },
                )
            }

            // 2 · Quick actions
            full { SectionHeader("Acciones rápidas") }
            full {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        POWER.forEach { p ->
                            QuickActionButton(p.icon, p.label, tone = p.tone, enabled = connected, modifier = Modifier.weight(1f), onClick = {
                                haptics.tick()
                                if (p.confirm != null && settings.confirmDestructive) confirm = p else power(p)
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickActionButton(Icons.AutoMirrored.Outlined.VolumeOff, "Silenciar", enabled = connected, modifier = Modifier.weight(1f), onClick = {
                            haptics.tick()
                            scope.launch { runCatching { vm.run("media", "volumeMute") }.onFailure { vm.post(friendlyMessage(it)) } }
                        })
                        QuickActionButton(Icons.Outlined.ContentPaste, "Portapapeles", enabled = connected, modifier = Modifier.weight(1f), onClick = { open(Routes.Clipboard) })
                        QuickActionButton(Icons.Outlined.Terminal, "Terminal", enabled = connected, modifier = Modifier.weight(1f), onClick = { open(Routes.Terminal) })
                        QuickActionButton(Icons.Outlined.Extension, "Plugins", enabled = connected, modifier = Modifier.weight(1f), onClick = { open(Routes.Plugins) })
                    }
                }
            }

            // 3 · System
            full {
                SectionHeader("Sistema") {
                    TextButton(onClick = { open(Routes.Monitor) }, enabled = stats != null) { Text("Detalles") }
                }
            }
            val s = stats
            if (s == null) {
                if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.AUTHENTICATING) {
                    items(4) { SkeletonBox(Modifier.fillMaxWidth(), 150.dp) }
                } else {
                    full {
                        EmptyState(Icons.Outlined.QueryStats, "Sin datos del PC", "Aparecerán en cuanto se conecte.")
                    }
                }
            } else {
                item {
                    SystemMetricCard(
                        Icons.Outlined.Memory, "CPU", formatPct(s.cpu),
                        fraction = (s.cpu / 100).toFloat(),
                        detail = listOfNotNull(s.cpuFreqMHz?.let { String.format(java.util.Locale.ROOT, "%.1f GHz", it / 1000) }, s.cpuTempC?.let { "${it.toInt()} °C" })
                            .joinToString(" · ").ifEmpty { info?.let { "${it.cpuCores} núcleos" } },
                        history = vm.history.cpu.toList(), historyCapacity = vm.history.capacity,
                        onClick = { open(Routes.Monitor) },
                    )
                }
                item {
                    SystemMetricCard(
                        Icons.Outlined.Storage, "RAM", formatPct(s.ramPct),
                        fraction = (s.ramPct / 100).toFloat(),
                        detail = "${formatMB(s.ramUsedMB)} de ${formatMB(s.ramTotalMB)}",
                        history = vm.history.ram.toList(), historyCapacity = vm.history.capacity,
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = { open(Routes.Monitor) },
                    )
                }
                s.gpu?.let { g ->
                    item {
                        SystemMetricCard(
                            Icons.Outlined.VideogameAsset, "GPU", formatPct(g.usage),
                            fraction = g.usage?.let { (it / 100).toFloat() },
                            detail = listOfNotNull(g.tempC?.let { "${it.toInt()} °C" },
                                g.vramUsedMB?.let { u -> "VRAM ${formatMB(u)}" + (g.vramTotalMB?.let { " / ${formatMB(it)}" } ?: "") })
                                .joinToString(" · ").ifEmpty { g.name },
                            history = vm.history.gpu.toList(), historyCapacity = vm.history.capacity,
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = { open(Routes.Monitor) },
                        )
                    }
                }
                s.disks.firstOrNull()?.let { d ->
                    item {
                        SystemMetricCard(
                            Icons.Outlined.SdStorage, "Disco ${d.name}", formatPct(d.usedPct),
                            fraction = (d.usedPct / 100).toFloat(),
                            detail = "${formatGB(d.freeGB)} libres de ${formatGB(d.totalGB)}",
                            onClick = { open(Routes.Monitor) },
                        )
                    }
                }
                full {
                    NetworkStatusCard(
                        s.net, latency, info?.lanIp ?: creds?.agentHost,
                        downHistory = vm.history.down.toList(), capacity = vm.history.capacity,
                        onClick = { open(Routes.Network) },
                    )
                }
            }

            // 4 · Tools
            full { SectionHeader("Herramientas") }
            tool(Icons.Outlined.QueryStats, "Monitor", "Gráficas y hardware", enabled = stats != null) { open(Routes.Monitor) }
            tool(Icons.AutoMirrored.Outlined.ListAlt, "Procesos", "Qué consume y cerrarlo", enabled = connected) { open(Routes.Processes) }
            tool(Icons.Outlined.Lan, "Red", "Interfaces, ping, conexiones", enabled = connected) { open(Routes.Network) }
            tool(Icons.Outlined.Folder, "Archivos", "Explorar y transferir",
                enabled = connected, badge = feature("files")?.takeIf { !it.enabled }?.let { "Desactivado" }) { open(Routes.Files) }
            tool(Icons.Outlined.Terminal, "Terminal", "PowerShell en el PC",
                enabled = connected, badge = feature("terminal")?.takeIf { !it.enabled }?.let { "Desactivado" }) { open(Routes.Terminal) }
            tool(Icons.Outlined.ContentPaste, "Portapapeles", "Historial, imágenes incluidas", enabled = connected) { open(Routes.Clipboard) }
            tool(Icons.Outlined.Extension, "Plugins", plugins?.let { p -> "${p.plugins.count { it.enabled }} activos" } ?: "Acciones propias", enabled = connected) { open(Routes.Plugins) }
        }
    }

    confirm?.let { p ->
        ConfirmDialog(
            title = "¿${p.label} el PC?",
            text = p.confirm ?: "",
            confirmLabel = p.label,
            icon = p.icon,
            destructive = p.tone != Tone.Normal,
            onConfirm = { power(p) },
            onDismiss = { confirm = null },
        )
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.tool(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, badge: String? = null, onClick: () -> Unit) =
    item {
        Card(
            onClick = onClick,
            enabled = enabled,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(badge ?: subtitle, style = MaterialTheme.typography.bodySmall,
                        color = if (badge != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
