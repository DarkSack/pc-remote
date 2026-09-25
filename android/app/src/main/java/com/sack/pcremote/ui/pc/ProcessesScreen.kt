package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.ProcessGroup
import com.sack.pcremote.net.ProcessList
import com.sack.pcremote.net.friendlyMessage
import com.sack.pcremote.session.AgentJson
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ══════════════════════════════════════════════════════════════
// Procesos: lo que consume ahora mismo, agrupado por programa
// (chrome × 24 cuenta como uno), ordenable y con "Finalizar".
// ══════════════════════════════════════════════════════════════

private enum class ProcSort(val key: String, val label: String) { Ram("ram", "Memoria"), Cpu("cpu", "CPU"), Name("name", "Nombre") }

@Composable
fun ProcessesScreen(vm: PcSession, back: () -> Unit) {
    val client = vm.client ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(ProcSort.Ram) }
    var query by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<ProcessList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ProcessGroup?>(null) }

    LaunchedEffect(state, sort) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        val sub = client.subscribe(
            "processes", "watch",
            buildJsonObject { put("group", true); put("sort", sort.key); put("limit", 300); put("intervalMs", 2500) },
            onError = { error = it.message },
        ) { data ->
            runCatching { AgentJson.decodeFromJsonElement(ProcessList.serializer(), data) }.getOrNull()?.let { list = it; error = null }
        }
        try { awaitCancellation() } finally { sub.cancel() }
    }

    fun kill(p: ProcessGroup) {
        scope.launch {
            runCatching {
                client.call("processes", "kill", buildJsonObject { putJsonArray("pids") { p.pids.forEach { add(it) } } })
            }.onSuccess { haptics.confirm(); vm.post("${p.name} finalizado") }
             .onFailure { haptics.reject(); vm.post("No se pudo finalizar ${p.name}: ${friendlyMessage(it)}") }
        }
    }

    val q = query.trim()
    val shown = remember(list, q) {
        list?.processes.orEmpty().filter { q.isEmpty() || it.name.contains(q, true) || it.title?.contains(q, true) == true }
    }

    SubScreen("Procesos", back, subtitle = list?.let { "${it.total} procesos · ${it.processes.size} programas" }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Buscar proceso o ventana") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Borrar búsqueda") } }) else null,
                    singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Ordenar por", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProcSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { haptics.tick(); sort = s }, label = { Text(s.label) })
                    }
                }
            }
            RequireConnection(vm) {
                when {
                    list == null && error != null -> ErrorState(com.sack.pcremote.net.AgentError("No se pudo leer la lista", error!!))
                    list == null -> SkeletonList(10)
                    shown.isEmpty() -> EmptyState(Icons.Outlined.SearchOff, "Sin resultados", "Ningún proceso coincide con \"$q\".")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                        items(shown, key = { it.name + it.pids.firstOrNull() }) { p ->
                            ProcessRow(p, onKill = {
                                haptics.tick()
                                if (settings.confirmDestructive) confirm = p else kill(p)
                            })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }

    confirm?.let { p ->
        ConfirmDialog(
            title = "¿Finalizar ${p.name}?",
            text = (if (p.count > 1) "Se cerrarán sus ${p.count} procesos. " else "") + "Lo que no esté guardado en ese programa se perderá.",
            confirmLabel = "Finalizar",
            icon = Icons.Outlined.Dangerous,
            onConfirm = { kill(p) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ProcessRow(p: ProcessGroup, onKill: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.count > 1) { Spacer(Modifier.width(8.dp)); Pill("× ${p.count}") }
            }
        },
        supportingContent = {
            Column {
                p.title?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("CPU ${String.format(java.util.Locale.ROOT, "%.1f", p.cpu)} % · ${formatMB(p.workingMB)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = {
            IconTile(Icons.AutoMirrored.Outlined.ListAlt, size = 36.dp,
                container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (p.isProtected) {
                Icon(Icons.Outlined.Shield, contentDescription = "Proceso del sistema, protegido", tint = MaterialTheme.colorScheme.outline)
            } else {
                IconButton(onClick = onKill) {
                    Icon(Icons.Outlined.Close, contentDescription = "Finalizar ${p.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
