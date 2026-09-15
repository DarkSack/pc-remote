package com.sack.pcremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.*
import com.sack.pcremote.ui.remote.*
import com.sack.pcremote.ui.theme.*
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

// ══════════════════════════════════════════════════════════════
// Pantalla de un PC. Tiene UNA conexión (AgentClient) que comparten todas
// las secciones: inicio (CPU/RAM, info, energía), touchpad, teclado,
// multimedia, apps y portapapeles. Las secciones son estado de esta
// pantalla, no rutas de navegación, justo para no abrir y autenticar una
// conexión nueva cada vez que se cambia de sección.
// ══════════════════════════════════════════════════════════════

private enum class Section(val title: String, val icon: ImageVector?) {
    Home("Inicio", null),
    Touchpad("Touchpad", Icons.Filled.TouchApp),
    Keyboard("Teclado", Icons.Filled.Keyboard),
    Media("Multimedia", Icons.Filled.MusicNote),
    Apps("Apps", Icons.Filled.Apps),
    Clipboard("Portapapeles", Icons.Filled.ContentPaste),
}

/** How long the PC connection survives with the app in the background. */
private const val BACKGROUND_GRACE_MS = 30_000L

private data class PowerAction(val action: String, val label: String, val color: Color, val confirm: String?)

private val POWER_ACTIONS = listOf(
    PowerAction("lock", "Bloquear", Accent, null),
    PowerAction("sleep", "Suspender", Warn, "¿Suspender el PC?"),
    PowerAction("logoff", "Cerrar sesión", Warn, "¿Cerrar la sesión de Windows? Se cerrarán las aplicaciones abiertas."),
    PowerAction("restart", "Reiniciar", Danger, "¿Reiniciar el PC ahora?"),
    PowerAction("shutdown", "Apagar", Danger, "¿Apagar el PC ahora?"),
)

@Composable
fun DashboardScreen(deviceId: String, store: CredentialsStore, onBack: () -> Unit) {
    val creds = remember { store.load(deviceId) }
    if (creds == null) {
        // Navigating is a side effect: never do it straight from composition.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val client = remember { AgentClient(creds) }
    val state by client.state.collectAsState()
    val error by client.error.collectAsState()
    var section by rememberSaveable { mutableStateOf(Section.Home) }
    var info  by remember { mutableStateOf<SystemInfo?>(null) }
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    // The connection follows the app. In the background it is kept for a short grace
    // period (hopping to another app and back should not reconnect), then dropped:
    // before, it stayed open — and kept retrying with backoff — for as long as Android
    // let the process live, costing battery and holding a session on the PC.
    val ctx = LocalContext.current
    val effectScope = rememberCoroutineScope()
    val background = remember { object { var drop: Job? = null } }
    LifecycleStartEffect(client) {
        background.drop?.cancel()
        background.drop = null
        // FAILED waits for the user ("Reintentar"): a revoked device must not retry on every return.
        if (client.state.value == ConnectionState.DISCONNECTED) client.connect()
        onStopOrDispose {
            background.drop = effectScope.launch { delay(BACKGROUND_GRACE_MS); client.disconnect() }
        }
    }
    DisposableEffect(client) {
        // Network back (Wi-Fi reconnected, switched networks): skip the rest of the backoff.
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = client.reconnectNow()
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
            client.disconnect()
        }
    }

    // Stats only matter on the home section; info is fetched once per connection.
    LaunchedEffect(state, section) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        if (info == null) {
            runCatching {
                val res = client.request("systeminfo", "info")
                if (res.success && res.data != null) {
                    val i = json.decodeFromJsonElement(SystemInfo.serializer(), res.data)
                    info = i
                    // Remember MAC + broadcast so the PC can be woken up later from the list.
                    val latest = store.load(deviceId)
                    if (latest != null && i.macAddress != null &&
                        (latest.macAddress != i.macAddress || latest.broadcast != i.broadcast)) {
                        store.save(latest.copy(macAddress = i.macAddress, broadcast = i.broadcast))
                    }
                }
            }
        }
        if (section != Section.Home) return@LaunchedEffect
        val sub = client.subscribe("systeminfo", "stats") { data ->
            runCatching { stats = json.decodeFromJsonElement(SystemStats.serializer(), data) }
        }
        try { awaitCancellation() } finally { sub.cancel() }
    }

    BackHandler(enabled = section != Section.Home) { section = Section.Home }

    val statusColor = when (state) {
        ConnectionState.CONNECTED -> Success
        ConnectionState.FAILED -> Danger
        ConnectionState.AUTHENTICATING, ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Warn
        ConnectionState.DISCONNECTED -> DimDark
    }

    Scaffold(containerColor = BgDark) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (section != Section.Home) section = Section.Home else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (section == Section.Home) info?.hostname ?: creds.agentName else section.title,
                        color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(statusColor, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (section == Section.Home) state.label() else "${creds.agentName} · ${state.label()}",
                            color = DimDark, fontSize = 12.sp,
                        )
                    }
                    error?.let { Text(it, color = Danger, fontSize = 11.sp) }
                }
                // FAILED never retries by itself (revoked device, wrong certificate,
                // unreachable address after a fix on the PC side): let the user try again.
                if (state == ConnectionState.FAILED) {
                    TextButton(onClick = { client.connect() }) { Text("Reintentar", color = Accent) }
                }
            }

            Box(Modifier.weight(1f)) {
                when (section) {
                    Section.Home -> HomeSection(
                        client = client, state = state, info = info, stats = stats,
                        onOpen = { section = it },
                        onUnpair = {
                            client.disconnect()
                            store.delete(deviceId)
                            onBack()
                        },
                    )
                    Section.Touchpad -> TouchpadPanel(client)
                    Section.Keyboard -> KeyboardPanel(client)
                    Section.Media -> MediaPanel(client, state)
                    Section.Apps -> AppsPanel(client, state, creds.certFingerprintHex)
                    Section.Clipboard -> ClipboardPanel(client, state)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSection(
    client: AgentClient,
    state: ConnectionState,
    info: SystemInfo?,
    stats: SystemStats?,
    onOpen: (Section) -> Unit,
    onUnpair: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PowerAction?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    fun run(p: PowerAction) {
        scope.launch {
            busy = true
            runCatching { client.request("system", p.action) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("CPU", stats?.let { "${it.cpu.toInt()}%" } ?: "—", modifier = Modifier.weight(1f))
            Tile("RAM", stats?.let { "${it.ramPct.toInt()}%" } ?: "—",
                 sub = stats?.let { "${"%.1f".format(it.ramUsedMB / 1024f)} / ${"%.1f".format(it.ramTotalMB / 1024f)} GB" },
                 modifier = Modifier.weight(1f))
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Section.entries.filter { it != Section.Home }.forEach { s ->
                SectionTile(s, enabled = state == ConnectionState.CONNECTED, modifier = Modifier.weight(1f)) { onOpen(s) }
            }
            // Keep the last row's tiles the same width as the others.
            Spacer(Modifier.weight(1f))
        }

        info?.let { i ->
            Card(shape = RoundedCornerShape(12.dp),
                 colors = CardDefaults.cardColors(containerColor = CardDark),
                 modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
                Column(Modifier.padding(16.dp)) {
                    Text("SISTEMA", color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Usuario", i.username)
                    InfoRow("OS", "${i.os} (${i.osBuild})")
                    InfoRow("CPU", "${i.cpuModel} · ${i.cpuCores}c")
                    InfoRow("Uptime", formatUptime(i.uptimeSec))
                    InfoRow("Zona", i.timezone)
                }
            }
        }

        Card(shape = RoundedCornerShape(12.dp),
             colors = CardDefaults.cardColors(containerColor = CardDark),
             modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
            Column(Modifier.padding(16.dp)) {
                Text("ENERGÍA", color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val canRun = state == ConnectionState.CONNECTED && !busy
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    POWER_ACTIONS.forEach { p ->
                        PowerBtn(p.label, p.color, canRun) { if (p.confirm == null) run(p) else pending = p }
                    }
                }
            }
        }

        TextButton(onClick = { confirmUnpair = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Desemparejar dispositivo", color = Danger, fontSize = 13.sp)
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.label) },
            text = { Text(p.confirm ?: "") },
            confirmButton = { TextButton(onClick = { pending = null; run(p) }) { Text(p.label, color = p.color) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Desemparejar") },
            text = { Text("Se borran las credenciales de este móvil. Para volver a usar el PC habrá que emparejar de nuevo.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("Desemparejar", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SectionTile(section: Section, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = modifier
            .height(88.dp)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            section.icon?.let { Icon(it, contentDescription = null, tint = if (enabled) Accent else MutedDark) }
            Spacer(Modifier.height(6.dp))
            Text(section.title, color = if (enabled) TextDark else MutedDark, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable private fun Tile(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    Card(shape = RoundedCornerShape(12.dp),
         colors = CardDefaults.cardColors(containerColor = CardDark),
         modifier = modifier.border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextDark, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            if (sub != null) Text(sub, color = MutedDark, fontSize = 11.sp)
        }
    }
}

@Composable private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = DimDark, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Text(v, color = TextDark, fontSize = 12.sp)
    }
}

@Composable private fun PowerBtn(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.3f)),
    ) { Text(label, color = Color(0xFF0B1224), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

private fun ConnectionState.label() = when (this) {
    ConnectionState.CONNECTED -> "Conectado"
    ConnectionState.CONNECTING -> "Conectando…"
    ConnectionState.AUTHENTICATING -> "Autenticando…"
    ConnectionState.RECONNECTING -> "Reconectando…"
    ConnectionState.FAILED -> "Sin conexión"
    ConnectionState.DISCONNECTED -> "Desconectado"
}

private fun formatUptime(sec: Long): String {
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    val parts = buildList { if (d > 0) add("${d}d"); if (h > 0) add("${h}h"); add("${m}m") }
    return parts.joinToString(" ")
}
