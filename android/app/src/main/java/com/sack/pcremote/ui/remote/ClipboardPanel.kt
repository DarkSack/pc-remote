package com.sack.pcremote.ui.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.net.ClipboardText
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Portapapeles en los dos sentidos.
//
// PC → móvil: clipboard.watch muestra lo que hay copiado en el PC en cuanto
// cambia; "Copiar en el móvil" lo pasa al portapapeles del teléfono.
// Móvil → PC: lo escrito o pegado en el campo va con clipboard.set.
//
// Nada se copia solo al móvil: Android avisa cada vez que una app escribe
// en el portapapeles, y hacerlo sin que el usuario lo pida sería molesto
// y poco transparente.
// ══════════════════════════════════════════════════════════════

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun ClipboardPanel(client: AgentClient, state: ConnectionState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneClipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var pc by remember { mutableStateOf<ClipboardText?>(null) }
    var outgoing by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        val sub = client.subscribe("clipboard", "watch") { data ->
            runCatching { json.decodeFromJsonElement(ClipboardText.serializer(), data) }.getOrNull()?.let { pc = it }
        }
        try { awaitCancellation() } finally { sub.cancel() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("EN EL PC", color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val current = pc
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
        ) {
            SelectionContainer {
                Text(
                    when {
                        current == null -> "Esperando al PC…"
                        current.length == 0 -> "(vacío o no es texto)"
                        else -> current.text
                    },
                    color = if (current?.length ?: 0 > 0) TextDark else MutedDark,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (current != null && current.length > current.text.length) {
            Text("Se muestran ${current.text.length} de ${current.length} caracteres.", color = MutedDark, fontSize = 11.sp)
        }
        Button(
            enabled = (current?.length ?: 0) > 0,
            onClick = {
                scope.launch {
                    // The stream carries a preview (4096 chars); fetch the full text to copy it.
                    val full = runCatching { client.request("clipboard", "get") }.getOrNull()
                    val text = full?.data?.let { json.decodeFromJsonElement(ClipboardText.serializer(), it).text } ?: current?.text.orEmpty()
                    phoneClipboard.setPrimaryClip(ClipData.newPlainText("PC", text))
                    Toast.makeText(ctx, "Copiado en el móvil", Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copiar en el móvil", fontWeight = FontWeight.Bold) }

        HorizontalDivider(color = BorderDark)

        Text("ENVIAR AL PC", color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = outgoing,
            onValueChange = { outgoing = it },
            minLines = 3,
            placeholder = { Text("Escribe o pega aquí") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                focusedBorderColor = Accent, unfocusedBorderColor = BorderDark,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    outgoing = phoneClipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Pegar del móvil") }
            Button(
                enabled = outgoing.isNotEmpty(),
                onClick = {
                    scope.launch {
                        val r = runCatching { client.request("clipboard", "set", buildJsonObject { put("text", outgoing) }) }
                        val ok = r.getOrNull()?.success == true
                        Toast.makeText(ctx,
                            if (ok) "Copiado en el PC" else "No se pudo: ${r.getOrNull()?.error?.message ?: r.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                modifier = Modifier.weight(1f),
            ) { Text("Enviar al PC", fontWeight = FontWeight.Bold) }
        }
    }
}
