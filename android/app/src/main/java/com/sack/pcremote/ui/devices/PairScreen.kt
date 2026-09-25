package com.sack.pcremote.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.PairPhase
import com.sack.pcremote.net.PairingClient
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.delay

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
    val haptics = haptics()
    var phase by remember { mutableStateOf(PairPhase.CONNECTING) }
    var info by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf(qrCode ?: "") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    val client = remember { PairingClient(host, port, agentName, if (fromQr) qrFingerprint else null) }

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
                delay(900)
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
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(size = 36.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(agentName, style = MaterialTheme.typography.titleLarge)
                        Text("$host:$port", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (fromQr) Text("Certificado verificado con el QR", style = MaterialTheme.typography.labelMedium,
                                         color = PcRemoteTheme.extended.success)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            AnimatedContent(phase, label = "pair") { p ->
                when (p) {
                    PairPhase.CONNECTING -> LoadingState("Conectando con el agente…")
                    PairPhase.CONFIRMING -> LoadingState("Verificando el código…")
                    PairPhase.WAITING_CODE -> Column {
                        Text(
                            if (fromQr) "Código leído del QR. Revisa el nombre de este móvil y confirma."
                            else "El PC muestra un código de 6 dígitos en una notificación. Escríbelo aquí:",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        info?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = PcRemoteTheme.extended.warning)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Código") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                            textStyle = MonoStyle.copy(fontSize = 30.sp, letterSpacing = 10.sp, textAlign = TextAlign.Center),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(32) },
                            label = { Text("Nombre de este móvil en el PC") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (code.length == 6 && deviceName.isNotBlank()) client.submitCode(code, deviceName.trim())
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            enabled = code.length == 6 && deviceName.isNotBlank(),
                            onClick = { client.submitCode(code, deviceName.trim()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text("Emparejar") }
                    }
                    PairPhase.DONE -> EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "Emparejado",
                        message = "Abriendo el panel de $agentName…",
                    )
                    PairPhase.ERROR -> ErrorState(
                        title = "No se pudo emparejar",
                        message = info ?: "El PC no aceptó el emparejamiento.",
                        icon = Icons.Outlined.LinkOff,
                        onRetry = onBack,
                        retryLabel = "Volver",
                    )
                }
            }
        }
    }
}
