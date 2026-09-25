package com.sack.pcremote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.AgentError
import com.sack.pcremote.net.PairPhase
import com.sack.pcremote.net.PairingClient
import com.sack.pcremote.ui.components.ErrorState
import com.sack.pcremote.ui.components.IconTile
import com.sack.pcremote.ui.components.rememberHaptics
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.extendedColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    host: String, port: Int, agentName: String,
    store: CredentialsStore,
    /** Paired and saved: open that PC straight away (receives the new deviceId). */
    onPaired: (String) -> Unit,
    onBack: () -> Unit,
    // From the panel's QR: the code is already known and the certificate is pinned from the start.
    qrCode: String? = null,
    qrFingerprint: String? = null,
) {
    val fromQr = qrCode != null && qrFingerprint != null
    var phase by remember { mutableStateOf(PairPhase.CONNECTING) }
    var info  by remember { mutableStateOf<String?>(null) }
    var code  by remember { mutableStateOf(qrCode ?: "") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    val client = remember { PairingClient(host, port, agentName, if (fromQr) qrFingerprint else null) }
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        client.start { p, i ->
            phase = p
            if (i != null) info = i
            if (p == PairPhase.DONE) client.lastResult?.let { store.savePairing(it) }
        }
    }
    DisposableEffect(Unit) { onDispose { client.cancel() } }
    LaunchedEffect(phase) {
        when (phase) {
            PairPhase.DONE -> {
                haptics.confirm()
                val id = client.lastResult?.deviceId ?: return@LaunchedEffect
                kotlinx.coroutines.delay(700)
                onPaired(id)
            }
            PairPhase.ERROR -> haptics.reject()
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emparejar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(agentName, style = MaterialTheme.typography.headlineSmall)
            Text("$host:$port", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            AnimatedContent(phase, label = "phase") { p ->
                when (p) {
                    PairPhase.CONNECTING -> Waiting("Conectando con el PC…")
                    PairPhase.CONFIRMING -> Waiting("Comprobando el código…")
                    PairPhase.WAITING_CODE -> Column {
                        Text(
                            if (fromQr) "Código leído del QR. Revisa el nombre con el que aparecerá este móvil y confirma."
                            else "Introduce el código de 6 dígitos que muestra el PC.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        info?.let { Text(it, color = MaterialTheme.extendedColors.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Código") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(32) },
                            label = { Text("Nombre de este móvil en el PC") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            enabled = code.length == 6 && deviceName.isNotBlank(),
                            onClick = { haptics.tick(); client.submitCode(code, deviceName.trim()) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Emparejar") }
                    }
                    PairPhase.DONE -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconTile(Icons.Outlined.CheckCircle, size = 72.dp,
                            container = MaterialTheme.extendedColors.successContainer, content = MaterialTheme.extendedColors.onSuccessContainer)
                        Spacer(Modifier.height(16.dp))
                        Text("Emparejado", style = MaterialTheme.typography.titleLarge)
                        Text("Abriendo el PC…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairPhase.ERROR -> ErrorState(
                        AgentError("No se pudo emparejar", info ?: "Sin detalles."),
                        icon = Icons.Outlined.LinkOff,
                        onRetry = onBack,
                        retryLabel = "Volver",
                    )
                }
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
