package com.sack.pcremote.ui.pc

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.session.TerminalSession
import com.sack.pcremote.session.TerminalSession.Kind
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.extendedColors
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
// Terminal: PowerShell en el PC. Cada comando es un stream: la salida
// llega por lotes (normal y de error por separado) y al final el código
// de salida y la carpeta actual (`cd` se recuerda entre comandos).
//
// Viene DESACTIVADA en el agente: se enciende en su panel, en el PC.
// ══════════════════════════════════════════════════════════════

@Composable
fun TerminalScreen(vm: PcSession, back: () -> Unit) {
    val client = vm.client ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val term = vm.terminal
    val haptics = rememberHaptics()
    val clipboard = rememberTextClipboard()
    val main = remember { Handler(Looper.getMainLooper()) }
    var info by remember { mutableStateOf<TerminalInfo?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        runCatching { vm.call("terminal", "info", serializer = TerminalInfo.serializer()) }
            .onSuccess { info = it; error = null; if (term.cwd == null) term.cwd = it.cwd }
            .onFailure { error = it }
    }
    // Follow the output while it arrives.
    LaunchedEffect(term.lines.size) { if (term.lines.isNotEmpty()) listState.animateScrollToItem(term.lines.lastIndex) }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || term.running) return
        haptics.tick()
        if (cmd == "cls" || cmd == "clear") { term.clear(); input = ""; return }
        term.remember(cmd)
        term.add(Kind.Command, cmd)
        term.running = true
        input = ""
        historyIndex = -1
        val params = buildJsonObject { put("command", cmd); term.cwd?.let { put("cwd", it) } }
        var sub: AgentClient.Subscription? = null
        fun finish() { term.running = false; term.cancel = null; sub?.cancel() }
        sub = client.subscribe("terminal", "exec", params, onError = { e ->
            main.post { term.add(Kind.Err, friendlyMessage(e)); finish() }
        }) { data ->
            val o = runCatching { data.jsonObject }.getOrNull() ?: return@subscribe
            main.post {
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "out", "err" -> {
                        val kind = if (o["type"]!!.jsonPrimitive.content == "err") Kind.Err else Kind.Out
                        o["lines"]?.jsonArray?.forEach { term.add(kind, it.jsonPrimitive.contentOrNull.orEmpty()) }
                    }
                    "exit" -> {
                        val code = o["code"]?.jsonPrimitive?.intOrNull ?: 0
                        o["cwd"]?.jsonPrimitive?.contentOrNull?.let { term.cwd = it }
                        o["error"]?.jsonPrimitive?.contentOrNull?.let { term.add(Kind.Err, it) }
                        val ms = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0
                        val truncated = o["truncated"]?.jsonPrimitive?.booleanOrNull == true
                        term.add(Kind.Info, buildString {
                            append(if (code == 0) "✓ " else "✗ código $code · ")
                            append(if (ms < 1000) "$ms ms" else String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0))
                            if (truncated) append(" · salida recortada")
                        })
                        if (code == 0) haptics.confirm() else haptics.reject()
                        finish()
                    }
                }
            }
        }
        term.cancel = {
            sub.cancel()
            main.post { if (term.running) { term.add(Kind.Info, "■ cancelado"); term.running = false; term.cancel = null } }
        }
    }

    SubScreen(
        "Terminal", back,
        subtitle = info?.let { "${it.shell} · ${it.user}@${it.host}" },
        actions = {
            IconButton(onClick = {
                clipboard.copy(term.lines.joinToString("\n") { if (it.kind == Kind.Command) "> ${it.text}" else it.text })
                vm.post("Salida copiada")
            }, enabled = term.lines.isNotEmpty()) { Icon(Icons.Outlined.ContentCopy, "Copiar toda la salida") }
            IconButton(onClick = { term.clear() }, enabled = term.lines.isNotEmpty()) { Icon(Icons.Outlined.ClearAll, "Limpiar") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).imePadding()) {
            RequireConnection(vm) {
                val e = error
                when {
                    e is RequestException && e.code == "FEATURE_DISABLED" -> EmptyState(
                        Icons.Outlined.Terminal,
                        "La terminal está desactivada",
                        "Por seguridad viene apagada. Actívala en el PC: abre PC Remote desde la bandeja → «Funciones y plugins» → Terminal.",
                    )
                    e != null && info == null -> ErrorState(AgentError.from(e), onRetry = { error = null; vm.retry() })
                    else -> Column(Modifier.fillMaxSize()) {
                        TerminalOutput(term, listState, Modifier.weight(1f).padding(horizontal = 12.dp))
                        if (term.history.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                term.history.take(10).forEach { h ->
                                    SuggestionChip(onClick = { input = h }, label = { Text(h, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        modifier = Modifier.widthIn(max = 220.dp))
                                }
                            }
                        }
                        CommandInput(
                            value = input,
                            onValueChange = { input = it },
                            cwd = term.cwd,
                            running = term.running,
                            onRun = { run(input) },
                            onCancel = { term.cancel?.invoke() },
                            onHistory = {
                                if (term.history.isNotEmpty()) {
                                    historyIndex = (historyIndex + 1) % term.history.size
                                    input = term.history[historyIndex]
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The dark, monospace output pane. Selectable, so any part can be copied. */
@Composable
fun TerminalOutput(term: TerminalSession, listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val ext = MaterialTheme.extendedColors
    Box(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(ext.terminalBackground)) {
        if (term.lines.isEmpty()) {
            Text(
                "Escribe un comando de PowerShell.\nEj.: Get-Process | Sort CPU -desc | Select -First 5",
                style = MonoStyle, color = ext.terminalDim, modifier = Modifier.padding(16.dp),
            )
        }
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
                items(term.lines, key = { it.id }) { l ->
                    when (l.kind) {
                        Kind.Command -> Text("> ${l.text}", style = MonoStyle, color = ext.terminalPrompt, modifier = Modifier.padding(top = 8.dp))
                        Kind.Out -> Text(l.text, style = MonoStyle, color = ext.terminalText)
                        Kind.Err -> Text(l.text, style = MonoStyle, color = ext.terminalError)
                        Kind.Info -> Text(l.text, style = MonoStyle, color = ext.terminalDim)
                    }
                }
            }
        }
    }
}

@Composable
fun CommandInput(
    value: String,
    onValueChange: (String) -> Unit,
    cwd: String?,
    running: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        if (cwd != null) {
            Text("PS $cwd>", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Comando", style = MonoStyle) },
                textStyle = MonoStyle,
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onRun() }),
                leadingIcon = { IconButton(onClick = onHistory, enabled = !running) { Icon(Icons.Outlined.History, "Comando anterior") } },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Stop, "Cancelar comando") }
            } else {
                FilledIconButton(onClick = onRun, enabled = value.isNotBlank(), modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Ejecutar")
                }
            }
        }
    }
}
