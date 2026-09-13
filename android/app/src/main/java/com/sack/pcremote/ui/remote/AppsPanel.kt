package com.sack.pcremote.ui.remote

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.net.AppEntry
import com.sack.pcremote.net.AppList
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Lanzador: la lista la calcula el agente (menú Inicio + registro + UWP) y
// la guarda 5 min en caché; el filtro de búsqueda es local, para que
// escribir no dispare una petición por letra.
// ══════════════════════════════════════════════════════════════

private val json = Json { ignoreUnknownKeys = true }

/** applications.list runs PowerShell on a cold cache; a few seconds is normal. */
private const val LIST_TIMEOUT_MS = 30_000L

@Composable
fun AppsPanel(client: AgentClient, state: ConnectionState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    suspend fun load(refresh: Boolean) {
        loading = true
        error = null
        val res = runCatching {
            client.request("applications", "list", buildJsonObject { put("refresh", refresh) }, timeoutMs = LIST_TIMEOUT_MS)
        }
        loading = false
        val r = res.getOrNull()
        if (r?.success == true && r.data != null) {
            apps = runCatching { json.decodeFromJsonElement(AppList.serializer(), r.data).applications }.getOrElse { emptyList() }
        } else {
            error = r?.error?.message ?: res.exceptionOrNull()?.message ?: "Error desconocido"
        }
    }

    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED && apps == null) load(refresh = false)
    }

    val visible = remember(apps, query) {
        val q = query.trim()
        apps.orEmpty().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar app") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                    focusedBorderColor = Accent, unfocusedBorderColor = BorderDark,
                ),
            )
            IconButton(onClick = { scope.launch { load(refresh = true) } }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Volver a leer las apps del PC", tint = Accent)
            }
        }

        when {
            loading && apps == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("Leyendo las apps del PC…", color = DimDark, fontSize = 13.sp)
                }
            }
            error != null && apps == null -> Text("No se pudo cargar la lista: $error", color = Danger, fontSize = 13.sp)
            else -> {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                Text("${visible.size} de ${apps.orEmpty().size}", color = MutedDark, fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { app ->
                        ListItem(
                            headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(sourceLabel(app.source), fontSize = 11.sp) },
                            colors = ListItemDefaults.colors(containerColor = BgDark, headlineColor = TextDark, supportingColor = MutedDark),
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val r = runCatching {
                                        client.request("applications", "launch", buildJsonObject { put("id", app.id) })
                                    }
                                    val ok = r.getOrNull()?.success == true
                                    Toast.makeText(ctx,
                                        if (ok) "Abriendo ${app.name}"
                                        else "No se pudo abrir: ${r.getOrNull()?.error?.message ?: r.exceptionOrNull()?.message}",
                                        Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun sourceLabel(source: String) = when (source) {
    "startmenu" -> "Menú Inicio"
    "registry" -> "Instalada"
    "uwp" -> "Microsoft Store / sistema"
    else -> source
}
