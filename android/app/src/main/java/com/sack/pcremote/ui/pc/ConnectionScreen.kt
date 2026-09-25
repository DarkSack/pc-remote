package com.sack.pcremote.ui.pc

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionProblem
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.launch

/**
 * What opening a PC looks like until the first connection: "Conectando con tu
 * PC…", then a short "Conectado" with name, IP and latency, or "PC no
 * disponible" with Reintentar / Encender / Ver detalles.
 */
@Composable
fun ConnectionScreen(session: PcSession, onExit: () -> Unit) {
    val state by session.state.collectAsState()
    val problem by session.problem.collectAsState()
    val creds by session.creds.collectAsState()
    val info by session.info.collectAsState()
    val latency by session.latencyMs.collectAsState()

    val phase = when {
        state == ConnectionState.CONNECTED -> Phase.CONNECTED
        state == ConnectionState.FAILED || (problem != null && state == ConnectionState.RECONNECTING) -> Phase.UNAVAILABLE
        else -> Phase.CONNECTING
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            IconButton(onClick = onExit, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tus equipos")
            }
            AnimatedContent(
                phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.align(Alignment.Center),
                label = "connection",
            ) { p ->
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (p) {
                        Phase.CONNECTING -> Connecting(creds.agentName, state)
                        Phase.CONNECTED -> Connected(info?.hostname ?: creds.agentName, info?.lanIp ?: creds.agentHost, latency)
                        Phase.UNAVAILABLE -> Unavailable(session, problem, onExit)
                    }
                }
            }
        }
    }
}

private enum class Phase { CONNECTING, CONNECTED, UNAVAILABLE }

@Composable
private fun Connecting(name: String, state: ConnectionState) {
    BrandMark(size = 56.dp)
    Spacer(Modifier.height(28.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(24.dp))
    Text("Conectando con tu PC…", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    ConnectionStatus(state)
}

@Composable
private fun Connected(name: String, ip: String, latency: Long?) {
    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = PcRemoteTheme.extended.success,
         modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(20.dp))
    Text("Conectado", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(name, style = MaterialTheme.typography.titleLarge)
    Text(ip, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (latency != null) Text("Latencia: $latency ms", style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Unavailable(session: PcSession, problem: ConnectionProblem?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val creds by session.creds.collectAsState()
    LaunchedEffect(Unit) { haptics.reject() }

    ErrorState(
        title = if (problem?.isFinal == true) problem.title else "PC no disponible",
        message = problem?.message ?: "Comprueba tu conexión.",
        detail = problem?.detail,
        icon = if (problem?.isFinal == true) Icons.Outlined.GppMaybe else Icons.Outlined.CloudOff,
        onRetry = { haptics.tick(); session.retry() },
        secondary = {
            val mac = creds.macAddress
            if (mac != null && problem?.isFinal != true) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = runCatching { WakeOnLan.wake(mac, creds.broadcast) }.isSuccess
                        if (ok) haptics.confirm() else haptics.reject()
                        Toast.makeText(ctx, if (ok) "Señal de encendido enviada" else "No se pudo enviar", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Encender")
                }
            }
        },
    )
    TextButton(onClick = onExit) { Text("Elegir otro equipo") }
}
