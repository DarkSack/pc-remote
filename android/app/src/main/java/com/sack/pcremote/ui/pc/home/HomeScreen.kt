package com.sack.pcremote.ui.pc.home

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.AppGraph
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.SystemInfo
import com.sack.pcremote.net.SystemStats
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.session.MetricHistory
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.pc.PcTool
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ══════════════════════════════════════════════════════════════
// Inicio: el Command Center. De arriba abajo, por importancia:
//   estado global (PcStatusCard) → acciones rápidas → monitorización
//   detallada (tarjetas con gráfica) → herramientas → info del equipo.
// En pantallas anchas, dos columnas: estado y acciones | métricas.
// ══════════════════════════════════════════════════════════════

private data class PowerAction(
    val action: String,
    val label: String,
    val icon: ImageVector,
    val confirm: String? = null,
    val destructive: Boolean = false,
    val done: String,
)

private val POWER_ACTIONS = listOf(
    PowerAction("lock", "Bloquear", Icons.Outlined.Lock, done = "PC bloqueado"),
    PowerAction("sleep", "Suspender", Icons.Outlined.Bedtime, "El PC se suspenderá. Podrás despertarlo con Wake-on-LAN si lo tiene activado.", done = "Suspendiendo el PC"),
    PowerAction("restart", "Reiniciar", Icons.Outlined.RestartAlt, "Se cerrarán las aplicaciones abiertas y el PC se reiniciará ahora.", destructive = true, done = "Reiniciando el PC"),
    PowerAction("shutdown", "Apagar", Icons.Outlined.PowerSettingsNew, "Se cerrarán las aplicaciones abiertas y el PC se apagará ahora.", destructive = true, done = "Apagando el PC"),
    PowerAction("hibernate", "Hibernar", Icons.Outlined.ModeStandby, "El PC guardará su estado en disco y se apagará.", done = "Hibernando el PC"),
    PowerAction("logoff", "Cerrar sesión", Icons.AutoMirrored.Outlined.Logout, "Se cerrará la sesión de Windows y las aplicaciones abiertas.", destructive = true, done = "Cerrando la sesión"),
)

@Composable
fun HomeScreen(
    session: PcSession,
    onOpenTool: (PcTool) -> Unit,
    onOpenTab: () -> Unit,
    onSwitchPc: () -> Unit,
) {
    val ctx = LocalContext.current
    val settings by AppGraph.get(ctx).settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val snackbar = remember { SnackbarHostState() }

    val state by session.state.collectAsState()
    val info by session.info.collectAsState()
    val stats by session.stats.collectAsState()
    val history by session.history.collectAsState()
    val latency by session.latencyMs.collectAsState()
    val creds by session.creds.collectAsState()
    val problem by session.problem.collectAsState()
    val connectedSince by session.client.connectedSince.collectAsState()
    val lastSync by session.lastSync.collectAsState()
    val plugins by session.plugins.collectAsState()
    val online = state == ConnectionState.CONNECTED

    var pending by remember { mutableStateOf<PowerAction?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun run(p: PowerAction) {
        scope.launch {
            busy = p.action
            val r = session.client.call("system", p.action, kotlinx.serialization.json.JsonObject.serializer())
            busy = null
            if (r.isSuccess) { haptics.confirm(); snackbar.showSnackbar(p.done) }
            else { haptics.reject(); snackbar.showSnackbar("No se pudo: ${r.exceptionOrNull()?.message}") }
        }
    }

    fun request(p: PowerAction) {
        haptics.tick()
        if (p.confirm != null && (settings.confirmDestructive || p.destructive)) pending = p else run(p)
    }

    fun toggleMute() {
        scope.launch {
            val r = session.client.call("media", "volumeMute", kotlinx.serialization.json.JsonObject.serializer())
            val muted = r.getOrNull()?.get("mute")?.jsonPrimitive?.booleanOrNull
            if (r.isSuccess) haptics.toggle(muted == true) else haptics.reject()
            snackbar.showSnackbar(when (muted) { true -> "PC silenciado"; false -> "Sonido activado"; null -> "No se pudo cambiar el sonido" })
        }
    }

    fun showDesktop() {
        haptics.tick()
        session.client.send("input", "keyPress", buildJsonObject { put("keys", "win+d") })
    }

    fun wake() {
        val mac = creds.macAddress ?: return
        scope.launch {
            val ok = runCatching { WakeOnLan.wake(mac, creds.broadcast) }.isSuccess
            if (ok) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (ok) "Señal de encendido enviada" else "No se pudo enviar la señal")
        }
    }

    PcScaffold(
        title = "Command Center",
        titleContent = { BrandLockup(subtitle = creds.agentName) },
        actions = {
            IconButton(onClick = onSwitchPc) { Icon(Icons.Outlined.Devices, contentDescription = "Cambiar de equipo") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp

            val status: @Composable () -> Unit = {
                PcStatusCard(
                    name = creds.agentName, state = state, info = info, stats = stats,
                    address = info?.lanIp ?: creds.agentHost, latencyMs = latency,
                    connectedSince = connectedSince, lastSync = lastSync, problem = problem,
                    onRetry = { haptics.tick(); session.retry() },
                    onWake = if (creds.macAddress != null) ::wake else null,
                )
            }
            val actions: @Composable () -> Unit = {
                SectionHeader("Acciones rápidas")
                val canPower = online && session.isAvailable("system")
                val tiles = POWER_ACTIONS.map { p ->
                    @Composable { m: Modifier ->
                        QuickActionButton(p.icon, p.label, { request(p) }, m, enabled = canPower,
                                          destructive = p.destructive, busy = busy == p.action)
                    }
                } + listOf<@Composable (Modifier) -> Unit>(
                    { m -> QuickActionButton(Icons.AutoMirrored.Outlined.VolumeOff, "Silenciar", ::toggleMute, m,
                                             enabled = online && session.isAvailable("media")) },
                    { m -> QuickActionButton(Icons.Outlined.DesktopWindows, "Escritorio", ::showDesktop, m,
                                             enabled = online && session.isAvailable("input")) },
                )
                Grid(tiles, columns = 4)
            }
            val tools: @Composable () -> Unit = {
                SectionHeader("Herramientas")
                val list = listOf(
                    Triple(PcTool.Terminal, "terminal", Tool("Terminal", "PowerShell y cmd", Icons.Outlined.Terminal)),
                    Triple(PcTool.Files, "files", Tool("Archivos", "Explorar el PC", Icons.Outlined.FolderOpen)),
                    Triple(PcTool.Network, "network", Tool("Red", "Interfaces y conexiones", Icons.Outlined.Hub)),
                    Triple(PcTool.Clipboard, "cliphistory", Tool("Portapapeles", "Historial e imágenes", Icons.Outlined.ContentPaste)),
                )
                Grid(list.map { (tool, domain, t) ->
                    @Composable { m: Modifier ->
                        ToolTile(t, available = plugins.let { session.isAvailable(domain) },
                                 modifier = m) { haptics.tick(); onOpenTool(tool) }
                    }
                } + listOf<@Composable (Modifier) -> Unit>({ m ->
                    ToolTile(Tool("Procesos", "Consumo y ventanas", Icons.Outlined.Memory), available = true, modifier = m) {
                        haptics.tick(); onOpenTab()
                    }
                }), columns = if (wide) 3 else 2)
            }
            val monitoring: @Composable () -> Unit = {
                SectionHeader("Sistema", subtitle = if (online) "En tiempo real · toca una tarjeta para ver su gráfica" else "Última lectura")
                Metrics(stats, info, history, latency, loading = online && stats == null)
            }
            val about: @Composable () -> Unit = { info?.let { SystemInfoCard(it) } }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        status(); actions(); tools(); about()
                        Spacer(Modifier.height(72.dp))
                    }
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        monitoring()
                        Spacer(Modifier.height(72.dp))
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    status(); actions(); monitoring(); tools(); about()
                    Spacer(Modifier.height(72.dp)) // room for the connection banner
                }
            }
        }
    }

    pending?.let { p ->
        ConfirmDialog(
            title = "¿${p.label}?",
            message = p.confirm ?: "",
            confirmLabel = p.label,
            icon = p.icon,
            destructive = p.destructive,
            onConfirm = { run(p) },
            onDismiss = { pending = null },
        )
    }
}

/** A simple non-lazy grid for a handful of tiles (inside a scrolling column). */
@Composable
private fun Grid(items: List<@Composable (Modifier) -> Unit>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            // Same height for the whole row, even when one label wraps to two lines.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class Tool(val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun ToolTile(tool: Tool, available: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tool.icon, contentDescription = null,
                 tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (available) tool.subtitle else "Desactivado en el PC", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Metrics(stats: SystemStats?, info: SystemInfo?, history: MetricHistory, latency: Long?, loading: Boolean) {
    val ext = PcRemoteTheme.extended
    if (loading) {
        repeat(3) { SkeletonCard(height = 112.dp) }
        return
    }
    val s = stats ?: run {
        Text("Sin datos todavía.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SystemMetricCard(
            title = "CPU", icon = Icons.Outlined.Memory, color = ext.cpu,
            headline = pct(s.cpu), fraction = (s.cpu / 100).toFloat(),
            details = listOf(
                "Temperatura" to (s.cpuTempC?.let { "${it.toInt()} °C" } ?: "—"),
                "Frecuencia" to (s.cpuFreqMHz?.let { "%.2f GHz".format(it / 1000.0) } ?: "—"),
                "Núcleos" to (info?.cpuCores?.takeIf { it > 0 }?.toString() ?: "—"),
            ),
            history = history.cpu,
            extra = { info?.cpuModel?.let { Caption(it) } },
        )
        SystemMetricCard(
            title = "Memoria", icon = Icons.Outlined.DeveloperBoard, color = ext.ram,
            headline = pct(s.ramPct), fraction = (s.ramPct / 100).toFloat(),
            details = listOf(
                "En uso" to formatMB(s.ramUsedMB),
                "Disponible" to formatMB((s.ramTotalMB - s.ramUsedMB).coerceAtLeast(0)),
                "Total" to formatMB(s.ramTotalMB),
            ),
            history = history.ram,
        )
        s.gpu?.let { g ->
            SystemMetricCard(
                title = "GPU", icon = Icons.Outlined.VideogameAsset, color = ext.gpu,
                headline = pct(g.usage), fraction = ((g.usage ?: 0.0) / 100).toFloat(),
                details = listOf(
                    "VRAM" to (g.vramUsedMB?.let { used -> g.vramTotalMB?.let { "${formatMB(used)} / ${formatMB(it)}" } ?: formatMB(used) } ?: "—"),
                    "Temperatura" to (g.tempC?.let { "${it.toInt()} °C" } ?: "—"),
                ),
                history = history.gpu,
                extra = { g.name?.let { Caption(it) } },
            )
        }
        if (s.disks.isNotEmpty()) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
                             modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Almacenamiento", style = MaterialTheme.typography.titleSmall)
                    }
                    s.disks.forEach { d ->
                        val warn = d.usedPct >= 90
                        MetricBar(
                            label = listOfNotNull(d.name, d.label?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            fraction = (d.usedPct / 100).toFloat(),
                            valueText = "${d.usedPct.toInt()}%",
                            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            supporting = "%.0f GB libres de %.0f GB".format(d.freeGB, d.totalGB),
                        )
                    }
                }
            }
        }
        s.net?.let { n ->
            SystemMetricCard(
                title = "Red", icon = Icons.Outlined.SwapVert, color = ext.net,
                headline = "↓ ${formatBitrate(n.rxBps)}",
                details = listOf(
                    "Bajada" to formatBitrate(n.rxBps),
                    "Subida" to formatBitrate(n.txBps),
                    "Ping" to (latency?.let { "$it ms" } ?: "—"),
                ),
                history = history.rx,
                historyMax = null,
                extra = {
                    Caption(listOfNotNull(n.iface, n.linkMbps?.let { "enlace $it Mbps" }).joinToString(" · "))
                },
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SystemInfoCard(i: SystemInfo) {
    SectionHeader("Este equipo")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            InfoRow("Procesador", i.cpuModel.ifBlank { "—" })
            InfoRow("Núcleos", i.cpuCores.toString())
            InfoRow("Memoria", formatMB(i.ramTotalMB))
            i.gpuName?.let { InfoRow("Gráfica", it + (i.vramTotalMB?.let { v -> " · ${formatMB(v)}" } ?: "")) }
            InfoRow("Sistema", "${i.os} (${i.osBuild})")
            InfoRow("Usuario", i.username)
            InfoRow("Zona horaria", i.timezone)
            i.agentVersion?.let { InfoRow("Agente", "v$it") }
        }
    }
}
