package com.sack.pcremote.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sack.pcremote.PcRemoteApplication
import com.sack.pcremote.R
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.net.DiscoveredAgent
import com.sack.pcremote.net.QrPayload
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.extendedColors
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════
// Pantalla de conexión: tus PCs (con "en la red" si mDNS los ve ahora
// mismo, y encendido por Wake-on-LAN) y los agentes nuevos de la red.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    app: PcRemoteApplication,
    onPair: (DiscoveredAgent) -> Unit,
    onPairQr: (QrPayload) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val store = app.store
    var paired by remember { mutableStateOf(store.listAll()) }
    val found = remember { mutableStateListOf<DiscoveredAgent>() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var manual by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        paired = store.listAll()
        app.discovery.scan().collect { agent ->
            // Paired PC seen at a new address (DHCP gave it another IP): follow it. Matched
            // by certificate fingerprint, never by name — a name is trivial to fake.
            val fp = agent.fingerprint
            if (fp != null) {
                var changed = false
                store.listAll()
                    .filter { it.certFingerprintHex.equals(fp, ignoreCase = true) && (it.agentHost != agent.host || it.agentPort != agent.port) }
                    .forEach { store.save(it.copy(agentHost = agent.host, agentPort = agent.port)); changed = true }
                if (changed) paired = store.listAll()
            }
            if (found.none { it.host == agent.host && it.port == agent.port }) found.add(agent)
        }
    }

    fun isOnline(c: AgentCredentials) = found.any {
        (it.fingerprint != null && it.fingerprint.equals(c.certFingerprintHex, ignoreCase = true)) ||
            (it.host == c.agentHost && it.port == c.agentPort)
    }
    val newAgents = found.filter { a -> paired.none { isSame(it, a) } }

    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { barcode ->
                val payload = QrPayload.parse(barcode.rawValue.orEmpty())
                if (payload != null) { haptics.confirm(); onPairQr(payload) }
                else scope.launch { snackbar.showSnackbar("Ese QR no es de PC Remote (o está incompleto).") }
            }
            .addOnFailureListener {
                scope.launch { snackbar.showSnackbar("No se pudo abrir el escáner. Empareja con el código.") }
            }
    }

    fun wake(c: AgentCredentials) {
        val mac = c.macAddress ?: return
        haptics.confirm()
        scope.launch {
            val msg = runCatching { WakeOnLan.wake(mac, c.broadcast) }
                .fold({ "Señal de encendido enviada a ${c.agentName}." }, { "No se pudo enviar: ${it.message}" })
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_logo_mark), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("PC Remote")
                            Text("Personal Command Center", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { paired = store.listAll(); found.clear() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Buscar de nuevo")
                    }
                },
                scrollBehavior = scrollBehavior,
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
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader("Tus PCs") }
            if (paired.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.Computer,
                        "Todavía no hay ningún PC",
                        "Abre PcRemote.exe en tu PC; se abrirá su panel con un QR. Escanéalo con el botón de abajo.",
                        actionLabel = "Escanear QR",
                        onAction = ::scanQr,
                    )
                }
            } else {
                items(paired, key = { it.deviceId }) { c ->
                    PairedPcCard(
                        c,
                        online = isOnline(c),
                        onClick = { haptics.tick(); onOpen(c.deviceId) },
                        onWake = if (c.macAddress != null && !isOnline(c)) ({ wake(c) }) else null,
                    )
                }
            }

            item { SectionHeader("En esta red") }
            if (newAgents.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando agentes por mDNS…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(newAgents, key = { "${it.host}:${it.port}" }) { agent ->
                    ListItem(
                        headlineContent = { Text(agent.name) },
                        supportingContent = { Text("${agent.host}:${agent.port}${agent.os?.let { " · $it" } ?: ""}", style = MonoStyle) },
                        leadingContent = { IconTile(Icons.Outlined.AddLink, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer) },
                        trailingContent = { TextButton(onClick = { onPair(agent) }) { Text("Emparejar") } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.animateContentSize(),
                    )
                }
            }
            // mDNS blocked by the router and no camera for the QR: type the address.
            item {
                TextButton(onClick = { manual = true }) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Conectar escribiendo la dirección")
                }
            }
        }
    }

    if (manual) ManualAddressDialog(
        onDismiss = { manual = false },
        onConnect = { host, port -> manual = false; onPair(DiscoveredAgent(name = host, host = host, port = port)) },
    )
}

/** Host and port by hand; pairing then goes through the code the PC shows. */
@Composable
private fun ManualAddressDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47820") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && !host.contains(' ') && portNumber != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conectar por dirección") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("La IP del PC aparece en el panel del agente y en el icono de la bandeja.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP o nombre del PC") }, singleLine = true,
                    placeholder = { Text("192.168.1.50") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Puerto") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConnect(host, portNumber!!) }) { Text("Conectar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun isSame(c: AgentCredentials, a: DiscoveredAgent) =
    (c.agentHost == a.host && c.agentPort == a.port) ||
        (a.fingerprint != null && c.certFingerprintHex.equals(a.fingerprint, ignoreCase = true))

@Composable
private fun PairedPcCard(c: AgentCredentials, online: Boolean, onClick: () -> Unit, onWake: (() -> Unit)?) {
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Computer, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(c.agentName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (online) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.outline, live = false, size = 6.dp)
                    Spacer(Modifier.width(2.dp))
                    Text(
                        if (online) "En la red" else "No visto ahora",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (online) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${c.agentHost}:${c.agentPort}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onWake != null) {
                FilledTonalIconButton(onClick = onWake, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Encender ${c.agentName} (Wake-on-LAN)")
                }
            } else {
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
