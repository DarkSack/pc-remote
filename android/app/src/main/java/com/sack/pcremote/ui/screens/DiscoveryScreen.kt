package com.sack.pcremote.ui.screens

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.DiscoveredAgent
import com.sack.pcremote.net.Discovery
import com.sack.pcremote.net.QrPayload
import com.sack.pcremote.net.WakeOnLan
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    store: CredentialsStore,
    discovery: Discovery,
    onPair: (DiscoveredAgent) -> Unit,
    onPairQr: (QrPayload) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var paired    by remember { mutableStateOf(store.listAll()) }
    val found     = remember { mutableStateListOf<DiscoveredAgent>() }
    val scope     = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        paired = store.listAll()
        launch {
            discovery.scan().collect { agent ->
                // Paired PC seen at a new address (DHCP gave it another IP): the
                // saved host would never connect again, so follow the PC. Matched by
                // certificate fingerprint, never by name — a name is trivial to fake,
                // and the pinned certificate still guards the connection anyway.
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
    }

    val newAgents = found.filter { a ->
        paired.none {
            (it.agentHost == a.host && it.agentPort == a.port) ||
            (a.fingerprint != null && it.certFingerprintHex.equals(a.fingerprint, ignoreCase = true))
        }
    }

    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { barcode ->
                val payload = QrPayload.parse(barcode.rawValue.orEmpty())
                if (payload != null) onPairQr(payload)
                else Toast.makeText(ctx, "Ese QR no es de PC Remote (o está incompleto).", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(ctx, "No se pudo abrir el escáner: ${it.message}. Empareja con el código.", Toast.LENGTH_LONG).show()
            }
    }

    fun wake(c: AgentCredentials) {
        val mac = c.macAddress ?: return
        scope.launch {
            val msg = runCatching { WakeOnLan.wake(mac, c.broadcast) }
                .fold({ "Paquete de encendido enviado a ${c.agentName}." }, { "No se pudo enviar: ${it.message}" })
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("PC Remote", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDark, titleContentColor = TextDark,
                ),
                actions = {
                    IconButton(onClick = { paired = store.listAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refrescar", tint = Accent)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = ::scanQr,
                containerColor = Accent,
                contentColor = BgDark,
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                text = { Text("Escanear QR", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Dispositivos emparejados") }
            if (paired.isEmpty()) {
                item { EmptyText("Ningún PC emparejado todavía. Abre el panel del agente y escanea su QR.") }
            } else {
                items(paired, key = { it.deviceId }) { c ->
                    PairedCard(c, onClick = { onOpen(c.deviceId) }, onWake = if (c.macAddress != null) ({ wake(c) }) else null)
                }
            }

            item { Spacer(Modifier.height(12.dp)); SectionHeader("Descubiertos en la red") }
            if (newAgents.isEmpty()) {
                item { EmptyText("Buscando agentes vía mDNS…") }
            } else {
                items(newAgents, key = { "${it.host}:${it.port}" }) { agent ->
                    DiscoveredCard(agent, onClick = { onPair(agent) })
                }
            }
            // Room for the FAB.
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = DimDark, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text = text, color = MutedDark, fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun PairedCard(c: AgentCredentials, onClick: () -> Unit, onWake: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark),
         shape = RoundedCornerShape(12.dp),
         modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))
             .clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.agentName, color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${c.agentHost}:${c.agentPort}", color = DimDark, fontSize = 12.sp)
            }
            // Only once the MAC is known (it is learned the first time the dashboard connects).
            if (onWake != null) {
                IconButton(onClick = onWake) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Encender (Wake-on-LAN)", tint = Success)
                }
            }
        }
    }
}

@Composable
private fun DiscoveredCard(a: DiscoveredAgent, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark),
         shape = RoundedCornerShape(12.dp),
         modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))
             .clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(a.name, color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${a.host}:${a.port}${a.os?.let { " · $it" } ?: ""}",
                 color = DimDark, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("Emparejar con código →", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
