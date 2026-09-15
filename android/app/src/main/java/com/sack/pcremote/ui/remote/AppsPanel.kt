package com.sack.pcremote.ui.remote

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.data.AppPrefs
import com.sack.pcremote.net.*
import com.sack.pcremote.ui.theme.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

// ══════════════════════════════════════════════════════════════
// Lanzador de apps del PC.
//
// Dinámico de verdad:
//   - La lista llega por applications.watch: si instalas o desinstalas algo
//     en el PC, aparece o desaparece aquí sin tocar nada.
//   - Iconos reales, pedidos solo para las filas que se ven y en lotes
//     (appicons.get, hasta 40 por petición cada ~120 ms), guardados en
//     memoria mientras la pantalla del PC esté abierta.
//   - Favoritas (estrella) y recientes (lo último que abriste), por PC.
// ══════════════════════════════════════════════════════════════

private val json = Json { ignoreUnknownKeys = true }

private const val FIRST_LOAD_TIMEOUT_MS = 30_000L
private const val ICON_BATCH = 40
private const val ICON_BATCH_DELAY_MS = 120L

/**
 * Loads icons on demand. `request(id)` is cheap and idempotent: ids already
 * loaded or in flight are ignored; the rest are batched into appicons.get.
 */
private class IconLoader(private val client: AgentClient) {
    val icons = mutableStateMapOf<String, ImageBitmap?>()
    private val requested = HashSet<String>()
    private val queue = Channel<String>(Channel.UNLIMITED)

    /**
     * Bumped after a reconnect. Rows key their request on it: a row already on
     * screen asks only once, so icons that failed while the connection was down
     * never loaded until the row was scrolled away and back.
     */
    var generation by mutableIntStateOf(0)
        private set

    fun request(id: String) {
        if (id in icons || !requested.add(id)) return
        queue.trySend(id)
    }

    /** Forget ids that never got an answer, and make visible rows ask again. */
    fun resetPending() {
        requested.retainAll(icons.keys)
        generation++
    }

    suspend fun run() {
        while (true) {
            val first = queue.receive()
            delay(ICON_BATCH_DELAY_MS) // let the rows that just scrolled in queue up too
            val batch = mutableListOf(first)
            while (batch.size < ICON_BATCH) batch += queue.tryReceive().getOrNull() ?: break

            val res = runCatching {
                client.request("appicons", "get", buildJsonObject { putJsonArray("ids") { batch.forEach { add(it) } } })
            }.getOrNull()
            if (res?.success != true || res.data == null) {
                batch.forEach { requested.remove(it) } // not connected: retry when the row shows again
                continue
            }
            val map = runCatching { json.decodeFromJsonElement(AppIcons.serializer(), res.data).icons }.getOrNull()
            if (map == null) { batch.forEach { requested.remove(it) }; continue }
            for (id in batch) {
                // Missing = the PC was still drawing it (null would mean "no icon"): ask again later.
                if (id !in map) { requested.remove(id); continue }
                icons[id] = map[id]?.let { b64 ->
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
        }
    }
}

@Composable
fun AppsPanel(client: AgentClient, state: ConnectionState, pcFingerprint: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(pcFingerprint) { AppPrefs(ctx.applicationContext, pcFingerprint) }
    val loader = remember(client) { IconLoader(client) }

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by remember { mutableStateOf(prefs.favorites()) }
    var recents by remember { mutableStateOf(prefs.recents()) }

    LaunchedEffect(loader) { loader.run() }

    fun apply(data: kotlinx.serialization.json.JsonElement) {
        val list = runCatching { json.decodeFromJsonElement(AppList.serializer(), data) }.getOrNull() ?: return
        // Ids are the LazyColumn keys; a duplicate would crash the list. The agent
        // dedupes them, this keeps an older agent from taking the app down.
        apps = list.applications.distinctBy { it.id }
        error = null
        // An empty list is far more likely a PC-side failure than "everything uninstalled".
        if (list.applications.isNotEmpty()) {
            prefs.prune(list.applications.mapTo(HashSet()) { it.id })
            favorites = prefs.favorites()
            recents = prefs.recents()
        }
    }

    // Live list. Re-subscribes on every reconnect (streams die with the socket).
    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        loader.resetPending()
        error = null
        val sub = client.subscribe("applications", "watch") { data -> apply(data) }
        try {
            // The first snapshot can take a few seconds (the PC lists Store apps via PowerShell).
            if (apps == null && withTimeoutOrNull(FIRST_LOAD_TIMEOUT_MS) { while (apps == null) delay(200) } == null) {
                error = "El PC no respondió con la lista de apps."
            }
            awaitCancellation()
        } finally {
            sub.cancel()
        }
    }

    fun launch(app: AppEntry) {
        scope.launch {
            val r = runCatching { client.request("applications", "launch", buildJsonObject { put("id", app.id) }) }
            val ok = r.getOrNull()?.success == true
            if (ok) recents = prefs.addRecent(app.id)
            Toast.makeText(ctx,
                if (ok) "Abriendo ${app.name} en el PC"
                else "No se pudo abrir: ${r.getOrNull()?.error?.message ?: r.exceptionOrNull()?.message}",
                Toast.LENGTH_SHORT).show()
        }
    }

    val all = apps.orEmpty()
    val byId = remember(all) { all.associateBy { it.id } }
    val q = query.trim()
    val filtered = remember(all, q) { if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) } }
    val favoriteApps = remember(byId, favorites) { favorites.mapNotNull { byId[it] }.sortedBy { it.name.lowercase() } }
    val recentApps = remember(byId, recents) { recents.mapNotNull { byId[it] } }

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
            IconButton(
                enabled = !refreshing && state == ConnectionState.CONNECTED,
                onClick = {
                    // Forces a rescan on the PC; the new list arrives through the stream.
                    scope.launch {
                        refreshing = true
                        val res = runCatching {
                            client.request("applications", "list", buildJsonObject { put("refresh", true) }, timeoutMs = FIRST_LOAD_TIMEOUT_MS)
                        }.getOrNull()
                        // Use the answer too: if the stream never started (the first load
                        // timed out), this button was the only way out and did nothing.
                        if (res?.success == true && res.data != null) apply(res.data)
                        else if (apps == null) error = res?.error?.message ?: "No se pudo leer la lista de apps."
                        refreshing = false
                    }
                },
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Volver a leer las apps del PC", tint = Accent)
            }
        }

        when {
            apps == null && error == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("Leyendo las apps del PC…", color = DimDark, fontSize = 13.sp)
                }
            }
            apps == null -> Text(error ?: "", color = Danger, fontSize = 13.sp)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (q.isEmpty() && recentApps.isNotEmpty()) {
                    item(key = "h-recent") { Header("Recientes") }
                    item(key = "recent-row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(recentApps, key = { "r-" + it.id }) { app ->
                                RecentTile(app, loader) { launch(app) }
                            }
                        }
                    }
                }
                if (q.isEmpty() && favoriteApps.isNotEmpty()) {
                    item(key = "h-fav") { Header("Favoritas") }
                    items(favoriteApps, key = { "f-" + it.id }) { app ->
                        AppRow(app, loader, favorite = true,
                            onToggleFavorite = { favorites = prefs.toggleFavorite(app.id) },
                            onClick = { launch(app) })
                    }
                }
                item(key = "h-all") {
                    Header(if (q.isEmpty()) "Todas (${all.size})" else "${filtered.size} de ${all.size}")
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") { Text("Ninguna app coincide con \"$q\".", color = MutedDark, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
                items(filtered, key = { "a-" + it.id }) { app ->
                    AppRow(app, loader, favorite = app.id in favorites,
                        onToggleFavorite = { favorites = prefs.toggleFavorite(app.id) },
                        onClick = { launch(app) })
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text.uppercase(), color = DimDark, fontSize = 11.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun AppIcon(app: AppEntry, loader: IconLoader, size: Dp) {
    // Asking only when the row is composed = only for what is on screen (LazyColumn).
    LaunchedEffect(app.id, loader.generation) { loader.request(app.id) }
    val icon = loader.icons[app.id]
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(BgAltDark), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp))
        else Icon(Icons.Filled.Apps, contentDescription = null, tint = MutedDark, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun AppRow(app: AppEntry, loader: IconLoader, favorite: Boolean, onToggleFavorite: () -> Unit, onClick: () -> Unit) {
    ListItem(
        leadingContent = { AppIcon(app, loader, 40.dp) },
        headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sourceLabel(app.source), fontSize = 11.sp) },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                     contentDescription = if (favorite) "Quitar de favoritas" else "Añadir a favoritas",
                     tint = if (favorite) Warn else MutedDark)
            }
        },
        colors = ListItemDefaults.colors(containerColor = BgDark, headlineColor = TextDark, supportingColor = MutedDark),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RecentTile(app: AppEntry, loader: IconLoader, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, loader, 48.dp)
        Spacer(Modifier.height(4.dp))
        Text(app.name, color = TextDark, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
             textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

private fun sourceLabel(source: String) = when (source) {
    "startmenu" -> "Menú Inicio"
    "registry" -> "Instalada"
    "uwp" -> "Microsoft Store / sistema"
    else -> source
}
