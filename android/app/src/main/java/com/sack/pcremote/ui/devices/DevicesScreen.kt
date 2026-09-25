package com.sack.pcremote.ui.devices

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sack.pcremote.AppGraph
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.net.DiscoveredAgent
import com.sack.pcremote.net.QrPayload
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════
// "Tus equipos": los PCs emparejados (con su estado en la red y el botón
// de encender por Wake-on-LAN), los agentes que aparecen por mDNS y el
// escáner del QR del panel.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    graph: AppGraph,
    onPair: (DiscoveredAgent) -> Unit,
    onPairQr: (QrPayload) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val store = graph.credentials
    val haptics = haptics()
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(store.listAll()) }
    val found = remember { mutableStateListOf<DiscoveredAgent>() }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    LaunchedEffect(Unit) {
        paired = store.listAll()
        graph.discovery.scan().collect { agent ->
            // Paired PC seen at a new address (DHCP gave it another IP): follow it.
            // Matched by certificate fingerprint, never by name — a name is trivial
            // to fake, and the pinned certificate still guards the connection anyway.
            val fp = agent.fingerprint
            if (fp != null) {
                var changed = false
                store.listAll()
                    .filter { it.certFingerprintHex.equals(fp, ignoreCase = true) &&
                              (it.agentHost != agent.host || it.agentPort != agent.port) }
                    .forEach { store.save(it.copy(agentHost = agent.host, agentPort = agent.port)); changed = true }
                if (changed) paired = store.listAll()
            }
            if (found.none { it.host == agent.host && it.port == agent.port }) found.add(agent)
        }
    }

    fun seen(c: AgentCredentials) = found.any {
        (it.fingerprint != null && it.fingerprint.equals(c.certFingerprintHex, ignoreCase = true)) ||
            (it.host == c.agentHost && it.port == c.agentPort)
    }
    val newAgents = found.filter { a ->
        paired.none {
            (it.agentHost == a.host && it.agentPort == a.port) ||
                (a.fingerprint != null && it.certFingerprintHex.equals(a.fingerprint, ignoreCase = true))
        }
    }

    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { barcode ->
                val payload = QrPayload.parse(barcode.rawValue.orEmpty())
                if (payload != null) { haptics.confirm(); onPairQr(payload) }
                else { haptics.reject(); Toast.makeText(ctx, "Ese QR no es de PC Remote (o está incompleto).", Toast.LENGTH_LONG).show() }
            }
            .addOnFailureListener {
                Toast.makeText(ctx, "No se pudo abrir el escáner. Empareja con el código.", Toast.LENGTH_LONG).show()
            }
    }

    fun wake(c: AgentCredentials) {
        val mac = c.macAddress ?: return
        scope.launch {
            val ok = runCatching { WakeOnLan.wake(mac, c.broadcast) }.isSuccess
            if (ok) haptics.confirm() else haptics.reject()
            Toast.makeText(ctx, if (ok) "Señal de encendido enviada a ${c.agentName}" else "No se pudo enviar la señal", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { BrandLockup(subtitle = "Tus equipos") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = ::scanQr,
                icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                text = { Text("Escanear QR") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                                           bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (paired.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Computer,
                        title = "Aún no hay equipos",
                        message = "Abre PcRemote.exe en tu PC: se abrirá su panel con un código QR. Escanéalo para emparejar este móvil.",
                        action = { Button(onClick = ::scanQr) { Text("Escanear QR") } },
                    )
                }
            } else {
                item { SectionHeader("Emparejados", subtitle = "Toca un equipo para abrir su panel de control") }
                items(paired, key = { it.deviceId }) { c ->
                    PairedPcCard(c, seen = seen(c), onClick = { haptics.tick(); onOpen(c.deviceId) },
                                 onWake = if (c.macAddress != null) ({ wake(c) }) else null)
                }
            }

            item {
                SectionHeader("En tu red", subtitle = "Agentes anunciados por mDNS")
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            if (newAgents.isEmpty()) {
                item {
                    Text(
                        "Buscando agentes de PC Remote en esta red Wi-Fi…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                items(newAgents, key = { "${it.host}:${it.port}" }) { agent ->
                    DiscoveredCard(agent, onPair = { onPair(agent) })
                }
            }
        }
    }
}

@Composable
private fun PairedPcCard(c: AgentCredentials, seen: Boolean, onClick: () -> Unit, onWake: (() -> Unit)?) {
    val ext = PcRemoteTheme.extended
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DesktopWindows, contentDescription = null,
                             tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(c.agentName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.agentHost}:${c.agentPort}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    StatusDot(if (seen) ext.success else MaterialTheme.colorScheme.outline, live = false, size = 6.dp)
                    Text(if (seen) "Visible en la red" else "No visto todavía",
                         style = MaterialTheme.typography.labelMedium,
                         color = if (seen) ext.success else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Only once the MAC is known (learned the first time the PC connects).
            if (onWake != null && !seen) {
                FilledTonalIconButton(onClick = onWake) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Encender ${c.agentName} (Wake-on-LAN)")
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiscoveredCard(a: DiscoveredAgent, onPair: () -> Unit) {
    OutlinedCard(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleSmall)
                Text("${a.host}:${a.port}${a.version?.let { " · v$it" } ?: ""}", style = MonoStyle,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onPair) { Text("Emparejar") }
        }
    }
}
