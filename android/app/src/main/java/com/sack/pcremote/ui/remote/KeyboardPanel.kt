package com.sack.pcremote.ui.remote

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.ui.components.rememberHaptics
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Teclado: un campo de texto que se envía entero (input.keyType, Unicode,
// no depende del idioma del teclado del PC) y una rejilla de teclas y
// combinaciones (input.keyPress).
//
// Se escribe y luego se envía, en lugar de mandar cada pulsación del
// teclado del móvil: el IME de Android corrige, sugiere y reemplaza
// palabras enteras, y replicar eso tecla a tecla en el PC es frágil.
// ══════════════════════════════════════════════════════════════

private data class Key(val label: String, val keys: String)

private val NAV_KEYS = listOf(
    Key("Esc", "esc"), Key("Tab", "tab"), Key("⌫", "backspace"), Key("Supr", "delete"),
    Key("Inicio", "home"), Key("↑", "up"), Key("Fin", "end"), Key("RePág", "pageup"),
    Key("←", "left"), Key("↓", "down"), Key("→", "right"), Key("AvPág", "pagedown"),
    Key("Enter", "enter"), Key("Espacio", "space"), Key("Win", "win"), Key("ImpPt", "printscreen"),
)

private val COMBOS = listOf(
    Key("Copiar", "ctrl+c"), Key("Pegar", "ctrl+v"), Key("Cortar", "ctrl+x"), Key("Deshacer", "ctrl+z"),
    Key("Todo", "ctrl+a"), Key("Guardar", "ctrl+s"), Key("Buscar", "ctrl+f"), Key("Rehacer", "ctrl+y"),
    Key("Alt+Tab", "alt+tab"), Key("Escritorio", "win+d"), Key("Cerrar", "alt+f4"), Key("Tareas", "ctrl+shift+esc"),
    Key("Nueva pest.", "ctrl+t"), Key("Cerrar pest.", "ctrl+w"), Key("Recargar", "f5"), Key("Pant. compl.", "f11"),
)

private val FUNCTION_KEYS = (1..12).map { Key("F$it", "f$it") }

@Composable
fun KeyboardPanel(client: AgentClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var pressEnter by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    fun press(keys: String) {
        haptics.tick()
        client.send("input", "keyPress", buildJsonObject { put("keys", keys) })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 4096) text = it },
            label = { Text("Texto para escribir en el PC") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pressEnter, onCheckedChange = { pressEnter = it })
            Text("Pulsar Enter al final", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Button(
                enabled = text.isNotEmpty() && !sending,
                onClick = {
                    val payload = if (pressEnter) text + "\n" else text
                    sending = true
                    scope.launch {
                        val res = runCatching {
                            client.request("input", "keyType", buildJsonObject { put("text", payload) })
                        }
                        sending = false
                        if (res.getOrNull()?.success == true) text = ""
                        else Toast.makeText(ctx, "No se pudo escribir: " +
                            (res.getOrNull()?.error?.message ?: res.exceptionOrNull()?.message), Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("Escribir") }
        }

        KeyGrid("Teclas", NAV_KEYS, ::press)
        KeyGrid("Atajos", COMBOS, ::press)
        KeyGrid("Función", FUNCTION_KEYS, ::press)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGrid(title: String, keys: List<Key>, onKey: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 4,
        modifier = Modifier.fillMaxWidth(),
    ) {
        keys.forEach { k ->
            FilledTonalButton(
                onClick = { onKey(k.keys) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(k.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
}
