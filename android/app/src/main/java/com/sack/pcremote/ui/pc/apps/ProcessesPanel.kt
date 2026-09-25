package com.sack.pcremote.ui.pc.apps

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.AppGraph
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.ProcessInfo
import com.sack.pcremote.net.ProcessList
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.NumericStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.ui.platform.LocalContext

// ══════════════════════════════════════════════════════════════
// Procesos activos: búsqueda, orden (memoria / CPU / nombre) y finalizar,
// con confirmación. Se refresca cada 3 s mientras está en pantalla.
// ══════════════════════════════════════════════════════════════

private enum class Sort(val label: String, val param: String) { Memory("Memoria", "memory"), Cpu("CPU", "cpu"), Name("Nombre", "name") }

@Composable
fun ProcessesPanel(session: PcSession, snackbar: SnackbarHostState) {
    val client = session.client
    val ctx = LocalContext.current
    val settings by AppGraph.get(ctx).settings.settings.collectAsState()
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Memory) }
    var list by remember { mutableStateOf<ProcessList?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf<ProcessInfo?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(sort, state, tick) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        while (isActive) {
            client.call("processes", "list", ProcessList.serializer(),
                buildJsonObject { put("limit", 150); put("sort", sort.param) })
                .onSuccess { list = it; error = null }
                .onFailure { if (list == null) error = it }
            delay(3_000)
        }
    }

    fun kill(p: ProcessInfo) {
        scope.launch {
            val r = client.call("processes", "kill", JsonObject.serializer(), buildJsonObject { put("pid", p.pid) })
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) "${p.name} finalizado" else "No se pudo: ${r.exceptionOrNull()?.message}")
            tick++
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Buscar proceso") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Sort.entries.forEach { s ->
                FilterChip(selected = sort == s, onClick = { haptics.tick(); sort = s }, label = { Text(s.label) },
                           leadingIcon = if (sort == s) ({ Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(16.dp)) }) else null)
            }
            Spacer(Modifier.weight(1f))
            list?.let { Text("${it.total}", style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
                             color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        val q = query.trim()
        val rows = list?.processes.orEmpty().filter { q.isEmpty() || it.name.contains(q, true) || it.windowTitle?.contains(q, true) == true }
        when {
            list == null && error == null -> SkeletonList()
            list == null -> ErrorState("No se pudieron leer los procesos", error?.message ?: "", onRetry = { tick++ })
            rows.isEmpty() -> EmptyState(Icons.Outlined.SearchOff, "Sin resultados", "Ningún proceso coincide con \"$q\".")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.pid }) { p ->
                    ProcessRow(p, onKill = { haptics.tick(); if (settings.confirmDestructive) confirm = p else kill(p) })
                }
            }
        }
    }

    confirm?.let { p ->
        ConfirmDialog(
            title = "¿Finalizar ${p.name}?",
            message = "Se cerrará el proceso (PID ${p.pid}) y los que dependan de él. Lo que no esté guardado se perderá.",
            confirmLabel = "Finalizar",
            icon = Icons.Outlined.Dangerous,
            onConfirm = { kill(p) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ProcessRow(p: ProcessInfo, onKill: () -> Unit) {
    val ext = PcRemoteTheme.extended
    ListItem(
        modifier = Modifier.animateContentSize(),
        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                p.windowTitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "CPU ${p.cpu?.let { "%.1f%%".format(it) } ?: "—"} · RAM ${formatMB(p.workingMB)} · PID ${p.pid}",
                    style = MaterialTheme.typography.bodySmall.merge(NumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            val cpu = (p.cpu ?: 0.0).toFloat()
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                CircularProgressIndicator(
                    progress = { (cpu / 100f).coerceIn(0f, 1f) },
                    color = if (cpu > 50) MaterialTheme.colorScheme.error else ext.cpu,
                    trackColor = ext.cpu.copy(alpha = 0.14f), strokeWidth = 3.dp, gapSize = 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp),
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            IconButton(onClick = onKill) {
                Icon(Icons.Outlined.Close, contentDescription = "Finalizar ${p.name}", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
