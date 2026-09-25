package com.sack.pcremote.ui.pc.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.TerminalInfo
import com.sack.pcremote.net.TerminalResult
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray

// ══════════════════════════════════════════════════════════════
// Terminal: PowerShell (o cmd) en el PC. Estética de terminal solo aquí:
// fondo propio, monoespaciada, prompt con el directorio actual. El resto
// de la app no se convierte en una terminal.
//
//   - Cada comando es un bloque: prompt + salida + errores (en rojo) +
//     "código 0 · 230 ms". Se puede seleccionar y copiar.
//   - Historial de comandos por PC (flechas y chips de recientes).
//   - `cd` funciona: el agente recuerda el directorio de cada sesión.
// El plugin viene desactivado: hay que activarlo en el panel del PC.
// ══════════════════════════════════════════════════════════════

private data class Block(val id: Long, val prompt: String, val command: String, val result: TerminalResult?, val error: String?)

private const val MAX_HISTORY = 50

@Composable
fun TerminalScreen(session: PcSession, onBack: () -> Unit) {
    val client = session.client
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val state by session.state.collectAsState()
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val ext = PcRemoteTheme.extended

    val prefs = remember(creds.certFingerprintHex) {
        ctx.getSharedPreferences("terminal_${creds.certFingerprintHex.take(16).lowercase()}", Context.MODE_PRIVATE)
    }
    val history = remember {
        mutableStateListOf<String>().apply {
            runCatching { JSONArray(prefs.getString("history", "[]")).let { a -> repeat(a.length()) { add(a.getString(it)) } } }
        }
    }
    var info by remember { mutableStateOf<TerminalInfo?>(null) }
    var shell by rememberSaveable { mutableStateOf("powershell") }
    var cwd by remember { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var shellMenu by remember { mutableStateOf(false) }
    val blocks = remember { mutableStateListOf<Block>() }
    val listState = rememberLazyListState()
    val available = plugins.let { session.isAvailable("terminal") }

    LaunchedEffect(state, available) {
        if (state != ConnectionState.CONNECTED || !available) return@LaunchedEffect
        client.call("terminal", "info", TerminalInfo.serializer()).onSuccess { info = it; if (cwd.isEmpty()) cwd = it.cwd }
    }
    LaunchedEffect(blocks.size) { if (blocks.isNotEmpty()) listState.animateScrollToItem(blocks.size - 1) }

    fun prompt(dir: String = cwd) = if (shell == "cmd") "$dir>" else "PS $dir>"

    fun saveToHistory(command: String) {
        history.remove(command)
        history.add(0, command)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        prefs.edit().putString("history", JSONArray(history.toList()).toString()).apply()
    }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        if (cmd == "cls" || cmd == "clear") { blocks.clear(); input = ""; return }
        haptics.key()
        saveToHistory(cmd)
        historyIndex = -1
        input = ""
        running = true
        val p = prompt()
        scope.launch {
            val r = client.call("terminal", "run", TerminalResult.serializer(),
                buildJsonObject { put("command", cmd); put("shell", shell); put("timeoutSec", 120) }, timeoutMs = 125_000)
            running = false
            r.onSuccess { res ->
                if (res.cwd.isNotBlank()) cwd = res.cwd
                if (res.exitCode == 0) haptics.confirm() else haptics.reject()
            }.onFailure { haptics.reject() }
            blocks.add(Block(System.nanoTime(), p, cmd, r.getOrNull(), r.exceptionOrNull()?.message))
        }
    }

    fun copy(text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal", text))
        haptics.confirm()
    }

    PcScaffold(
        title = "Terminal",
        subtitle = info?.let { "${it.user}@${it.host}" } ?: creds.agentName,
        onBack = onBack,
        actions = {
            if (available) {
                Box {
                    TextButton(onClick = { shellMenu = true }) {
                        Text(when (shell) { "cmd" -> "cmd"; "pwsh" -> "pwsh"; else -> "PowerShell" })
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Elegir shell")
                    }
                    DropdownMenu(shellMenu, onDismissRequest = { shellMenu = false }) {
                        (info?.shells ?: listOf("powershell", "cmd")).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(when (s) { "cmd" -> "Símbolo del sistema (cmd)"; "pwsh" -> "PowerShell 7 (pwsh)"; else -> "Windows PowerShell" }) },
                                onClick = { shell = s; shellMenu = false; haptics.tick() },
                                trailingIcon = if (s == shell) ({ Icon(Icons.Outlined.Check, null) }) else null,
                            )
                        }
                    }
                }
                IconButton(onClick = { copy(blocks.joinToString("\n\n") { b -> render(b) }) }, enabled = blocks.isNotEmpty()) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar todo")
                }
                IconButton(onClick = { haptics.tick(); blocks.clear() }, enabled = blocks.isNotEmpty()) {
                    Icon(Icons.Outlined.ClearAll, contentDescription = "Limpiar")
                }
            }
        },
    ) { padding ->
        if (!available) {
            Box(Modifier.padding(padding)) { PluginOffState("La terminal", onRefresh = session::refreshPlugins) }
            return@PcScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // ── Output ──
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                    .background(ext.terminalBackground, MaterialTheme.shapes.medium),
            ) {
                if (blocks.isEmpty() && !running) {
                    Column(Modifier.padding(16.dp)) {
                        Text(prompt(cwd.ifEmpty { "~" }), style = MonoStyle, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Escribe un comando abajo. Se ejecuta en el PC como tu usuario.\n`cd` cambia de carpeta; `cls` limpia la pantalla.",
                             style = MonoStyle, color = ext.terminalText.copy(alpha = 0.6f))
                    }
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
                    items(blocks, key = { it.id }) { b -> TerminalOutput(b, onCopy = { copy(render(b)) }) }
                    if (running) item(key = "running") {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(prompt(), style = MonoStyle, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }

            // ── Recent commands ──
            AnimatedVisibility(history.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history.take(12)) { h ->
                        SuggestionChip(onClick = { input = h }, label = { Text(h, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }

            // ── Input ──
            CommandInput(
                prompt = prompt(cwd.ifEmpty { "~" }),
                value = input,
                onValueChange = { input = it },
                enabled = state == ConnectionState.CONNECTED && !running,
                onRun = { run(input) },
                onHistory = { dir ->
                    if (history.isEmpty()) return@CommandInput
                    historyIndex = (historyIndex + dir).coerceIn(-1, history.lastIndex)
                    input = if (historyIndex < 0) "" else history[historyIndex]
                },
            )
        }
    }
}

private fun render(b: Block): String = buildString {
    append(b.prompt).append(' ').append(b.command)
    b.result?.stdout?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    b.result?.stderr?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    b.error?.let { append('\n').append(it) }
}

@Composable
private fun TerminalOutput(b: Block, onCopy: () -> Unit) {
    val ext = PcRemoteTheme.extended
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            SelectionContainer(Modifier.weight(1f)) {
                Text("${b.prompt} ${b.command}", style = MonoStyle, color = scheme.primary)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar salida", tint = ext.terminalText.copy(alpha = 0.5f),
                     modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer {
            Column {
                b.result?.stdout?.takeIf { it.isNotBlank() }?.let { Text(it, style = MonoStyle, color = ext.terminalText) }
                b.result?.stderr?.takeIf { it.isNotBlank() }?.let { Text(it, style = MonoStyle, color = scheme.error) }
                b.error?.let { Text(it, style = MonoStyle, color = scheme.error) }
            }
        }
        val r = b.result
        if (r != null) {
            Text(
                buildString {
                    append(if (r.timedOut) "tiempo agotado" else "código ${r.exitCode}")
                    append(" · ${r.durationMs} ms")
                    if (r.truncated) append(" · salida recortada")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (r.exitCode == 0) ext.terminalText.copy(alpha = 0.45f) else scheme.error.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CommandInput(
    prompt: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, onRun: () -> Unit, onHistory: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Text(prompt, style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                 color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("> _", style = MonoStyle) },
                    textStyle = MonoStyle,
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                                      imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onRun() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onHistory(1) }) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Comando anterior") }
                IconButton(onClick = { onHistory(-1) }) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Comando siguiente") }
                FilledIconButton(onClick = onRun, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ejecutar")
                }
            }
        }
    }
}
