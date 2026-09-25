package com.sack.pcremote.ui.pc.control

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.ui.components.SectionHeader
import com.sack.pcremote.ui.components.haptics
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Teclado: un campo de texto que se envía entero (input.keyType, Unicode,
// no depende del idioma del teclado del PC), teclas sueltas y teclas de
// función. Ctrl / Alt / Mayús / Win se quedan "pegadas": tocas Ctrl y
// luego C y se manda ctrl+c.
//
// Se escribe y luego se envía, en lugar de mandar cada pulsación: el IME
// de Android corrige y reemplaza palabras enteras, y replicar eso tecla a
// tecla en el PC es frágil.
// ══════════════════════════════════════════════════════════════

private data class Key(val label: String, val keys: String, val description: String = label)

private val NAV_KEYS = listOf(
    Key("Esc", "esc", "Escape"), Key("Tab", "tab", "Tabulador"), Key("⌫", "backspace", "Borrar"), Key("Supr", "delete", "Suprimir"),
    Key("Inicio", "home"), Key("↑", "up", "Flecha arriba"), Key("Fin", "end"), Key("RePág", "pageup", "Retroceder página"),
    Key("←", "left", "Flecha izquierda"), Key("↓", "down", "Flecha abajo"), Key("→", "right", "Flecha derecha"), Key("AvPág", "pagedown", "Avanzar página"),
    Key("Enter", "enter"), Key("Espacio", "space"), Key("ImpPt", "printscreen", "Imprimir pantalla"), Key("Insert", "insert"),
)

private val FUNCTION_KEYS = (1..12).map { Key("F$it", "f$it") }

private val MODIFIERS = listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Mayús", "win" to "Win")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardPanel(client: AgentClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    var text by rememberSaveable { mutableStateOf("") }
    var pressEnter by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val mods = remember { mutableStateListOf<String>() }

    fun press(key: String) {
        haptics.key()
        val combo = (MODIFIERS.map { it.first }.filter { it in mods } + key).joinToString("+")
        client.send("input", "keyPress", buildJsonObject { put("keys", combo) })
        mods.clear()
    }

    fun send() {
        val payload = if (pressEnter) text + "\n" else text
        sending = true
        scope.launch {
            val res = runCatching { client.request("input", "keyType", buildJsonObject { put("text", payload) }) }
            sending = false
            if (res.getOrNull()?.success == true) { haptics.confirm(); text = "" }
            else {
                haptics.reject()
                Toast.makeText(ctx, "No se pudo escribir: " +
                    (res.getOrNull()?.error?.message ?: res.exceptionOrNull()?.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 4096) text = it },
            label = { Text("Texto para escribir en el PC") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pressEnter, onCheckedChange = { haptics.toggle(it); pressEnter = it })
                Spacer(Modifier.width(8.dp))
                Text("Pulsar Enter al final", style = MaterialTheme.typography.bodyMedium)
            }
            Button(enabled = text.isNotEmpty() && !sending, onClick = ::send) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Escribir")
            }
        }

        SectionHeader("Modificadores", subtitle = "Se aplican a la siguiente tecla")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODIFIERS.forEach { (key, label) ->
                FilterChip(
                    selected = key in mods,
                    onClick = { haptics.toggle(key !in mods); if (key in mods) mods.remove(key) else mods.add(key) },
                    label = { Text(label) },
                )
            }
        }

        SectionHeader("Teclas")
        KeyGrid(NAV_KEYS, ::press)
        SectionHeader("Función")
        KeyGrid(FUNCTION_KEYS, ::press)
        if (mods.isNotEmpty()) {
            SectionHeader("Letras y números")
            KeyGrid(("abcdefghijklmnopqrstuvwxyz0123456789").map { Key(it.uppercase(), it.toString()) }, ::press, columns = 6)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGrid(keys: List<Key>, onKey: (String) -> Unit, columns: Int = 4) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = columns,
        modifier = Modifier.fillMaxWidth(),
    ) {
        keys.forEach { k ->
            FilledTonalButton(
                onClick = { onKey(k.keys) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = k.description },
            ) { Text(k.label, style = MonoStyle, maxLines = 1) }
        }
    }
}
