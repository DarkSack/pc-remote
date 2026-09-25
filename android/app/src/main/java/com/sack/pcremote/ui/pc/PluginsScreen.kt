package com.sack.pcremote.ui.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.extendedColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
// Plugins: funciones opcionales del agente (terminal, archivos…) y
// plugins de la carpeta del PC. Activar/desactivar se hace SOLO en el
// panel del PC; aquí se ven y se ejecutan sus acciones (con sus
// parámetros, confirmación y salida).
// ══════════════════════════════════════════════════════════════

/** Material Symbols names used in plugin.json → the closest bundled icon. */
private fun symbol(name: String?): ImageVector = when (name?.lowercase()) {
    "terminal" -> Icons.Outlined.Terminal
    "folder", "folder_open" -> Icons.Outlined.Folder
    "lan", "network" -> Icons.Outlined.Lan
    "dns" -> Icons.Outlined.Dns
    "network_ping", "ping" -> Icons.Outlined.NetworkPing
    "delete" -> Icons.Outlined.Delete
    "monitoring", "query_stats" -> Icons.Outlined.QueryStats
    "build", "construction" -> Icons.Outlined.Build
    "power", "power_settings_new" -> Icons.Outlined.PowerSettingsNew
    "play", "play_arrow" -> Icons.Outlined.PlayArrow
    "download" -> Icons.Outlined.Download
    "settings" -> Icons.Outlined.Settings
    "wifi" -> Icons.Outlined.Wifi
    "volume", "volume_up" -> Icons.AutoMirrored.Outlined.VolumeUp
    "screenshot", "image" -> Icons.Outlined.Image
    "code" -> Icons.Outlined.Code
    "games", "sports_esports" -> Icons.Outlined.SportsEsports
    "clipboard", "content_paste" -> Icons.Outlined.ContentPaste
    else -> Icons.Outlined.Extension
}

private data class Pending(val plugin: Plugin, val action: PluginAction, val values: Map<String, String>? = null)

@Composable
fun PluginsScreen(vm: PcSession, back: () -> Unit) {
    val client = vm.client ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.plugins.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var askParams by remember { mutableStateOf<Pending?>(null) }
    var askConfirm by remember { mutableStateOf<Pending?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, PluginRunResult>?>(null) }

    LaunchedEffect(state) { if (state == ConnectionState.CONNECTED) vm.refreshPlugins() }

    fun execute(p: Pending) {
        val key = "${p.plugin.id}/${p.action.id}"
        running = key
        haptics.tick()
        scope.launch {
            val params = buildJsonObject {
                put("plugin", p.plugin.id)
                put("action", p.action.id)
                putJsonObject("params") {
                    p.action.params.forEach { param ->
                        val v = p.values?.get(param.id) ?: return@forEach
                        when (param.type) {
                            "number" -> v.toDoubleOrNull()?.let { put(param.id, it) } ?: put(param.id, v)
                            "bool" -> put(param.id, v.toBoolean())
                            else -> put(param.id, v)
                        }
                    }
                }
            }
            val timeout = ((if (p.action.timeoutSec > 0) p.action.timeoutSec else 30) + 10) * 1000L
            runCatching { vm.call("plugins", "run", params, PluginRunResult.serializer(), timeout) }
                .onSuccess { r ->
                    val ok = (r.exitCode ?: 0) == 0 && !r.timedOut
                    if (ok) haptics.confirm() else haptics.reject()
                    if (p.action.output || !ok) result = p.action.label to r
                    else vm.post("${p.action.label}: hecho")
                }
                .onFailure { haptics.reject(); vm.post("${p.action.label}: ${friendlyMessage(it)}") }
            running = null
        }
    }

    fun start(plugin: Plugin, action: PluginAction) {
        val p = Pending(plugin, action)
        when {
            action.params.isNotEmpty() -> askParams = p
            action.confirm != null -> askConfirm = p
            else -> execute(p)
        }
    }

    SubScreen(
        "Plugins", back,
        subtitle = catalog?.let { c -> "${c.plugins.count { it.enabled }} de ${c.plugins.size} activos" },
        actions = {
            IconButton(onClick = { scope.launch { refreshing = true; vm.refreshPlugins(); refreshing = false } }, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, "Volver a leer los plugins")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            RequireConnection(vm) {
                val c = catalog
                if (c == null) { SkeletonList(6); return@RequireConnection }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Row(Modifier.padding(16.dp)) {
                                Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Se activan en el PC", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("Abre PC Remote desde la bandeja del PC → «Funciones y plugins». Para añadir plugins, copia su carpeta en:",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    c.folder?.let {
                                        SelectionContainer { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 4.dp)) }
                                    }
                                }
                            }
                        }
                    }

                    if (c.features.isNotEmpty()) {
                        item { SectionHeader("Funciones del agente") }
                        items(c.features, key = { "f-" + it.id }) { f ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                ListItem(
                                    headlineContent = { Text(f.name) },
                                    supportingContent = { Text(f.description) },
                                    leadingContent = { IconTile(symbol(f.icon)) },
                                    trailingContent = { EnabledPill(f.enabled) },
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                )
                            }
                        }
                    }

                    item { SectionHeader("Plugins") }
                    if (c.plugins.isEmpty()) {
                        item {
                            EmptyState(Icons.Outlined.Extension, "Sin plugins",
                                "Un plugin es una carpeta con un plugin.json que describe acciones (programas con sus argumentos). Mira docs/PLUGINS.md.")
                        }
                    }
                    items(c.plugins, key = { "p-" + it.id }) { p ->
                        PluginCard(p, running, onRun = { a -> start(p, a) })
                    }
                }
            }
        }
    }

    askParams?.let { p ->
        ParamsDialog(p.plugin, p.action,
            onDismiss = { askParams = null },
            onRun = { values ->
                askParams = null
                val next = p.copy(values = values)
                if (p.action.confirm != null) askConfirm = next else execute(next)
            })
    }
    askConfirm?.let { p ->
        ConfirmDialog(
            title = p.action.label,
            text = p.action.confirm ?: "",
            confirmLabel = "Ejecutar",
            icon = symbol(p.action.icon),
            onConfirm = { execute(p) },
            onDismiss = { askConfirm = null },
        )
    }
    result?.let { (label, r) -> ResultSheet(label, r) { result = null } }
}

@Composable
private fun EnabledPill(enabled: Boolean) {
    val ext = MaterialTheme.extendedColors
    if (enabled) Pill("Activado", color = ext.successContainer, contentColor = ext.onSuccessContainer)
    else Pill("Desactivado")
}

@Composable
private fun PluginCard(p: Plugin, running: String?, onRun: (PluginAction) -> Unit) {
    val usable = p.enabled && p.errors.isEmpty()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Column {
                        p.description?.let { Text(it) }
                        Text(listOfNotNull(p.version?.let { "v$it" }, p.author, if (p.kind == "assembly") "módulo .NET" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingContent = { IconTile(symbol(p.icon)) },
                trailingContent = { EnabledPill(p.enabled) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            p.errors.forEach { e ->
                Text("⚠ $e", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (p.kind == "assembly" && p.enabled && !p.loaded) {
                Text("Se cargará al reiniciar el agente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.warning,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (p.actions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            p.actions.forEach { a ->
                val key = "${p.id}/${a.id}"
                ListItem(
                    headlineContent = { Text(a.label) },
                    supportingContent = a.description?.let { { Text(it) } },
                    leadingContent = { Icon(symbol(a.icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        if (running == key) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else FilledTonalIconButton(onClick = { onRun(a) }, enabled = usable && running == null) {
                            Icon(Icons.Outlined.PlayArrow, "Ejecutar ${a.label}")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = usable && running == null) { onRun(a) },
                )
            }
        }
    }
}

private fun defaultOf(param: PluginParam): String = when (val d = param.default) {
    null, is JsonNull -> if (param.type == "bool") "false" else param.options?.firstOrNull().orEmpty()
    is JsonPrimitive -> d.content
    else -> d.toString()
}

@Composable
private fun ParamsDialog(plugin: Plugin, action: PluginAction, onDismiss: () -> Unit, onRun: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { action.params.forEach { put(it.id, defaultOf(it)) } } }
    val missing = action.params.any { it.required && it.type != "bool" && values[it.id].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(symbol(action.icon), null) },
        title = { Text(action.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                action.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(plugin.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action.params.forEach { param ->
                    when {
                        param.type == "bool" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(param.label, Modifier.weight(1f))
                            Switch(checked = values[param.id] == "true", onCheckedChange = { values[param.id] = it.toString() })
                        }
                        param.type == "choice" && !param.options.isNullOrEmpty() -> Column {
                            Text(param.label, style = MaterialTheme.typography.labelLarge)
                            param.options.forEach { opt ->
                                Row(Modifier.fillMaxWidth().clickable { values[param.id] = opt }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = values[param.id] == opt, onClick = { values[param.id] = opt })
                                    Text(opt)
                                }
                            }
                        }
                        else -> OutlinedTextField(
                            value = values[param.id].orEmpty(),
                            onValueChange = { values[param.id] = it },
                            label = { Text(param.label + if (param.required) "" else " (opcional)") },
                            placeholder = param.placeholder?.let { { Text(it) } },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = if (param.type == "number") KeyboardType.Decimal else KeyboardType.Text),
                            supportingText = if (param.type == "number" && (param.min != null || param.max != null))
                                ({ Text("Entre ${param.min?.let { fmt(it) } ?: "−∞"} y ${param.max?.let { fmt(it) } ?: "∞"}") }) else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(values.toMap()) }, enabled = !missing) { Text("Ejecutar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultSheet(label: String, r: PluginRunResult, onDismiss: () -> Unit) {
    val clipboard = rememberTextClipboard()
    val ext = MaterialTheme.extendedColors
    val ok = (r.exitCode ?: 0) == 0 && !r.timedOut
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            r.timedOut -> "Tiempo agotado"
                            r.exitCode == null -> "Iniciado"
                            else -> "Código de salida ${r.exitCode}"
                        } + if (r.truncated) " · salida recortada" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ok) ext.success else MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { clipboard.copy(listOfNotNull(r.stdout, r.stderr).joinToString("\n")) }) {
                    Icon(Icons.Outlined.ContentCopy, "Copiar salida")
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium).background(ext.terminalBackground)) {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (r.stdout.isNullOrBlank() && r.stderr.isNullOrBlank()) Text("(sin salida)", style = MonoStyle, color = ext.terminalDim)
                        r.stdout?.takeIf { it.isNotBlank() }?.let { Text(it.trimEnd(), style = MonoStyle, color = ext.terminalText) }
                        r.stderr?.takeIf { it.isNotBlank() }?.let { Text(it.trimEnd(), style = MonoStyle, color = ext.terminalError) }
                    }
                }
            }
        }
    }
}
