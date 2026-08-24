package com.sack.pcremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.PairPhase
import com.sack.pcremote.net.PairingClient
import com.sack.pcremote.ui.theme.*

@Composable
fun PairScreen(
    host: String, port: Int, agentName: String,
    store: CredentialsStore,
    onDone: () -> Unit,
) {
    var phase by remember { mutableStateOf(PairPhase.CONNECTING) }
    var info  by remember { mutableStateOf<String?>(null) }
    var code  by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    val client = remember { PairingClient(host, port, agentName) }

    LaunchedEffect(Unit) {
        client.start { p, i ->
            phase = p
            if (i != null) info = i
            if (p == PairPhase.DONE) {
                client.lastResult?.let { store.save(it) }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { client.cancel() } }
    LaunchedEffect(phase) {
        if (phase == PairPhase.DONE) { kotlinx.coroutines.delay(700); onDone() }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Emparejando con", color = DimDark, fontSize = 13.sp)
        Text(agentName, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("$host:$port", color = DimDark, fontSize = 13.sp)

        Spacer(Modifier.height(32.dp))
        when (phase) {
            PairPhase.CONNECTING -> Centered { CircularProgressIndicator(color = Accent); Spacer(Modifier.height(12.dp)); Text("Conectando al agente…", color = TextDark) }
            PairPhase.WAITING_CODE -> {
                Text("Mira la notificación en el PC. Introduce el código de 6 dígitos:", color = DimDark, fontSize = 14.sp)
                info?.let { Text(it, color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = TextStyle(color = TextDark, fontSize = 32.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                    singleLine = true,
                    placeholder = { Text("000000", color = MutedDark, fontSize = 32.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                        focusedBorderColor = Accent, unfocusedBorderColor = BorderDark,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Nombre visible en el PC:", color = DimDark, fontSize = 12.sp)
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it.take(32) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                        focusedBorderColor = Accent, unfocusedBorderColor = BorderDark,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = code.length == 6 && deviceName.isNotBlank(),
                    onClick = { client.submitCode(code, deviceName.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentDim),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirmar", color = TextDark, fontWeight = FontWeight.Bold) }
            }
            PairPhase.CONFIRMING -> Centered { CircularProgressIndicator(color = Accent); Spacer(Modifier.height(12.dp)); Text("Verificando código…", color = TextDark) }
            PairPhase.DONE -> Centered {
                Text("✓ Emparejado", color = Success, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Volviendo…", color = DimDark, fontSize = 13.sp)
            }
            PairPhase.ERROR -> Centered {
                Text("Error de emparejamiento", color = Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(info ?: "Sin detalles", color = DimDark, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = CardDark)) {
                    Text("Volver", color = TextDark)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
}
