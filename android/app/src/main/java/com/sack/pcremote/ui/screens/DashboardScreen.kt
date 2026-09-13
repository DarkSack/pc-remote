package com.sack.pcremote.ui.screens

import android.app.AlertDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.*
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// ══════════════════════════════════════════════════════════════
// Dashboard: tiles CPU/RAM (subscribe systeminfo.stats) + info
// del sistema (request systeminfo.info) + botones de power.
// ══════════════════════════════════════════════════════════════

@Composable
fun DashboardScreen(deviceId: String, store: CredentialsStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val creds = remember { store.load(deviceId) }
    if (creds == null) {
        // Navigating is a side effect: never do it straight from composition.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val client = remember { AgentClient(creds) }
    val state by client.state.collectAsState()
    val error by client.error.collectAsState()
    var info  by remember { mutableStateOf<SystemInfo?>(null) }
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    var busy  by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    DisposableEffect(Unit) {
        client.connect()
        onDispose { client.disconnect() }
    }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        val sub = client.subscribe("systeminfo", "stats") { data ->
            runCatching {
                stats = json.decodeFromJsonElement(SystemStats.serializer(), data)
            }
        }
        try {
            // `info` lives in the systeminfo module; asking `system.info` got
            // INVALID_COMMAND back and the card never showed.
            runCatching {
                val res = client.request("systeminfo", "info")
                if (res.success && res.data != null) info = json.decodeFromJsonElement(SystemInfo.serializer(), res.data)
            }
            awaitCancellation()
        } finally {
            // Leaving CONNECTED (or the screen) cancels this effect: drop the stream.
            sub.cancel()
        }
    }

    val statusColor = when (state) {
        ConnectionState.CONNECTED -> Success
        ConnectionState.FAILED -> Danger
        ConnectionState.AUTHENTICATING, ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Warn
        ConnectionState.DISCONNECTED -> DimDark
    }

    Scaffold(containerColor = BgDark) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(info?.hostname ?: creds.agentName, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(statusColor, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(state.label(), color = DimDark, fontSize = 12.sp)
                    }
                    error?.let { Text(it, color = Danger, fontSize = 11.sp) }
                }
            }

            // Tiles
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("CPU",  stats?.let { "${it.cpu.toInt()}%" } ?: "—", modifier = Modifier.weight(1f))
                Tile("RAM",  stats?.let { "${it.ramPct.toInt()}%" } ?: "—",
                     sub = stats?.let { "${"%.1f".format(it.ramUsedMB/1024f)} / ${"%.1f".format(it.ramTotalMB/1024f)} GB" },
                     modifier = Modifier.weight(1f))
            }

            // Info card
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

            // Power
            Card(shape = RoundedCornerShape(12.dp),
                 colors = CardDefaults.cardColors(containerColor = CardDark),
                 modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
                Column(Modifier.padding(16.dp)) {
                    Text("POWER", color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val canRun = state == ConnectionState.CONNECTED && !busy
                    val onAction: (String, Boolean) -> Unit = { action, destructive ->
                        scope.launch {
                            val proceed = if (destructive) confirmAsync(ctx, action) else true
                            if (proceed) {
                                busy = true
                                runCatching { client.request("system", action) }
                                busy = false
                            }
                        }
                    }
                    FlowRow {
                        PowerBtn("Bloquear",  Accent,  canRun) { onAction("lock", false) }
                        PowerBtn("Suspender", Warn,    canRun) { onAction("sleep", true) }
                        PowerBtn("Log off",   Warn,    canRun) { onAction("logoff", true) }
                        PowerBtn("Reiniciar", Danger,  canRun) { onAction("restart", true) }
                        PowerBtn("Apagar",    Danger,  canRun) { onAction("shutdown", true) }
                    }
                }
            }

            TextButton(onClick = {
                client.disconnect()
                store.delete(deviceId)
                onBack()
            }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Desemparejar dispositivo", color = Danger, fontSize = 13.sp)
            }
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
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
    ) { Text(label, color = Color(0xFF0B1224), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun FlowRow(content: @Composable () -> Unit) {
    // Fila que envuelve — API experimental de Compose foundation.
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        content = { content() },
    )
}

private fun ConnectionState.label() = when (this) {
    ConnectionState.CONNECTED -> "Conectado"
    ConnectionState.CONNECTING -> "Conectando…"
    ConnectionState.AUTHENTICATING -> "Autenticando…"
    ConnectionState.RECONNECTING -> "Reconectando…"
    ConnectionState.FAILED -> "Falló"
    ConnectionState.DISCONNECTED -> "Desconectado"
}

private fun formatUptime(sec: Long): String {
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    val parts = buildList { if (d > 0) add("${d}d"); if (h > 0) add("${h}h"); add("${m}m") }
    return parts.joinToString(" ")
}

private suspend fun confirmAsync(ctx: Context, action: String): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        AlertDialog.Builder(ctx)
            .setTitle("Confirmar")
            .setMessage("¿$action el PC? Esta acción es inmediata.")
            .setPositiveButton("Sí") { d, _ -> d.dismiss(); cont.resumeWith(Result.success(true)) }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss(); cont.resumeWith(Result.success(false)) }
            .setOnCancelListener { cont.resumeWith(Result.success(false)) }
            .show()
    }
