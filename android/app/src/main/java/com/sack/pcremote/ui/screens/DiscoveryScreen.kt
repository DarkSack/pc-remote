package com.sack.pcremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.DiscoveredAgent
import com.sack.pcremote.net.Discovery
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    store: CredentialsStore,
    discovery: Discovery,
    onPair: (DiscoveredAgent) -> Unit,
    onOpen: (String) -> Unit,
) {
    var paired    by remember { mutableStateOf(store.listAll()) }
    val found     = remember { mutableStateListOf<DiscoveredAgent>() }
    val scope     = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        paired = store.listAll()
        launch {
            discovery.scan().collect { agent ->
                if (found.none { it.host == agent.host && it.port == agent.port }) found.add(agent)
            }
        }
    }

    val newAgents = found.filter { a -> paired.none { it.agentHost == a.host && it.agentPort == a.port } }

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
        }
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
                item { EmptyText("Ningún PC emparejado todavía.") }
            } else {
                items(paired, key = { it.deviceId }) { c ->
                    PairedCard(c, onClick = { onOpen(c.deviceId) })
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
            item { Spacer(Modifier.height(24.dp)) }
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
private fun PairedCard(c: AgentCredentials, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark),
         shape = RoundedCornerShape(12.dp),
         modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(12.dp))
             .clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(c.agentName, color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${c.agentHost}:${c.agentPort}", color = DimDark, fontSize = 12.sp)
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
            Text("Emparejar →", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
