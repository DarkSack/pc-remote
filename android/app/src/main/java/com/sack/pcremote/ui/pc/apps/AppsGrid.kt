package com.sack.pcremote.ui.pc.apps

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.data.AppPrefs
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ══════════════════════════════════════════════════════════════
// Lanzador de apps del PC, en rejilla.
//
//   - La lista llega por applications.watch: instalar o desinstalar algo en
//     el PC la actualiza sola.
//   - Iconos reales, pedidos solo para lo que se ve y en lotes.
//   - Favoritas y recientes, por PC.
//   - "En ejecución": se cruzan las ventanas abiertas (windows.list) con el
//     nombre de la app. Tocar una app abierta ofrece Enfocar / Cerrar /
//     Abrir otra; una cerrada se abre directamente. Mantener pulsado abre
//     siempre las opciones.
// ══════════════════════════════════════════════════════════════

private const val FIRST_LOAD_TIMEOUT_MS = 30_000L
private const val ICON_BATCH = 40
private const val ICON_BATCH_DELAY_MS = 120L
private const val WINDOWS_REFRESH_MS = 5_000L

/** Loads icons on demand, batched into appicons.get. */
private class IconLoader(private val client: AgentClient) {
    val icons = mutableStateMapOf<String, ImageBitmap?>()
    private val requested = HashSet<String>()
    private val queue = Channel<String>(Channel.UNLIMITED)

    /** Bumped after a reconnect so tiles already on screen ask again. */
    var generation by mutableIntStateOf(0)
        private set

    fun request(id: String) {
        if (id in icons || !requested.add(id)) return
        queue.trySend(id)
    }

    fun resetPending() {
        requested.retainAll(icons.keys)
        generation++
    }

    suspend fun run() {
        while (true) {
            val first = queue.receive()
            delay(ICON_BATCH_DELAY_MS) // let the tiles that just scrolled in queue up too
            val batch = mutableListOf(first)
            while (batch.size < ICON_BATCH) batch += queue.tryReceive().getOrNull() ?: break

            val map = client.call("appicons", "get", AppIcons.serializer(),
                buildJsonObject { putJsonArray("ids") { batch.forEach { add(it) } } }).getOrNull()?.icons
            if (map == null) { batch.forEach { requested.remove(it) }; continue }
            for (id in batch) {
                // Missing = the PC was still drawing it: ask again later.
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

/** Best-effort match between an installed app and the open windows. */
internal fun windowsOf(app: AppEntry, windows: List<WindowInfo>): List<WindowInfo> {
    val name = app.name.lowercase()
    val compact = name.filter { it.isLetterOrDigit() }
    return windows.filter { w ->
        val proc = w.process?.lowercase()?.removeSuffix(".exe").orEmpty()
        (proc.length >= 3 && (compact.contains(proc) || proc.contains(compact))) ||
            (name.length >= 4 && w.title.lowercase().endsWith(name))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsGrid(session: PcSession, snackbar: SnackbarHostState) {
    val client = session.client
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val state by session.state.collectAsState()
    val creds by session.creds.collectAsState()
    val prefs = remember(creds.certFingerprintHex) { AppPrefs(ctx.applicationContext, creds.certFingerprintHex) }
    val loader = remember(client) { IconLoader(client) }

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by remember { mutableStateOf(prefs.favorites()) }
    var recents by remember { mutableStateOf(prefs.recents()) }
    var windows by remember { mutableStateOf<List<WindowInfo>>(emptyList()) }
    var sheetFor by remember { mutableStateOf<AppEntry?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(loader) { loader.run() }

    fun apply(list: AppList) {
        // Ids are the grid keys; a duplicate would crash it.
        apps = list.applications.distinctBy { it.id }
        error = null
        if (list.applications.isNotEmpty()) {
            prefs.prune(list.applications.mapTo(HashSet()) { it.id })
            favorites = prefs.favorites()
            recents = prefs.recents()
        }
    }

    // Live list; re-subscribes on every reconnect.
    LaunchedEffect(client) {
        client.state.collect { st ->
            if (st == ConnectionState.CONNECTED) loader.resetPending()
        }
    }
    LaunchedEffect(client) {
        client.stream("applications", "watch").collect { data ->
            client.decode(AppList.serializer(), data)?.let(::apply)
        }
    }
    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED || apps != null) return@LaunchedEffect
        // The first snapshot can take a few seconds (the PC reads the Start menu).
        if (withTimeoutOrNull(FIRST_LOAD_TIMEOUT_MS) { while (apps == null) delay(200) } == null) {
            error = "El PC no respondió con la lista de apps."
        }
    }
    // Open windows, for the "running" dots.
    LaunchedEffect(client) {
        while (isActive) {
            if (session.isAvailable("windows")) {
                client.call("windows", "list", WindowList.serializer()).getOrNull()?.let { windows = it.windows }
            }
            delay(WINDOWS_REFRESH_MS)
        }
    }

    fun launch(app: AppEntry) {
        scope.launch {
            val r = client.call("applications", "launch", JsonObject.serializer(), buildJsonObject { put("id", app.id) })
            if (r.isSuccess) { haptics.confirm(); recents = prefs.addRecent(app.id); snackbar.showSnackbar("Abriendo ${app.name} en el PC") }
            else { haptics.reject(); snackbar.showSnackbar("No se pudo abrir: ${r.exceptionOrNull()?.message}") }
        }
    }

    fun windowAction(w: WindowInfo, action: String, done: String) {
        scope.launch {
            val r = client.call("windows", action, JsonObject.serializer(), buildJsonObject { put("hwnd", w.hwnd) })
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) done else "No se pudo: ${r.exceptionOrNull()?.message}")
            client.call("windows", "list", WindowList.serializer()).getOrNull()?.let { windows = it.windows }
        }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            client.call("applications", "list", AppList.serializer(), buildJsonObject { put("refresh", true) }, timeoutMs = FIRST_LOAD_TIMEOUT_MS)
                .onSuccess(::apply)
                .onFailure { if (apps == null) error = it.message ?: "No se pudo leer la lista de apps." }
            refreshing = false
        }
    }

    val all = apps.orEmpty()
    val byId = remember(all) { all.associateBy { it.id } }
    val q = query.trim()
    val filtered = remember(all, q) { if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) } }
    val favoriteApps = remember(byId, favorites) { favorites.mapNotNull { byId[it] }.sortedBy { it.name.lowercase() } }
    val recentApps = remember(byId, recents) { recents.mapNotNull { byId[it] }.take(8) }
    val running = remember(all, windows) { all.filter { windowsOf(it, windows).isNotEmpty() }.mapTo(HashSet()) { it.id } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar app") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Borrar búsqueda") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = !refreshing && state == ConnectionState.CONNECTED, onClick = ::refresh) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, contentDescription = "Volver a leer las apps del PC")
            }
        }

        when {
            apps == null && error == null -> AppsSkeleton()
            apps == null -> ErrorState("Sin lista de apps", error ?: "", onRetry = ::refresh)
            all.isEmpty() -> EmptyState(Icons.Outlined.Apps, "No se encontraron apps",
                "Tu PC aún no ha informado de ninguna aplicación.",
                action = { OutlinedButton(onClick = ::refresh) { Text("Actualizar") } })
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (q.isEmpty() && recentApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-recent") { SectionHeader("Recientes") }
                    items(recentApps, key = { "r-" + it.id }) { app ->
                        ApplicationCard(app, loader, running = app.id in running, favorite = app.id in favorites,
                            onClick = { if (app.id in running) sheetFor = app else launch(app) },
                            onLongClick = { haptics.longPress(); sheetFor = app })
                    }
                }
                if (q.isEmpty() && favoriteApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-fav") { SectionHeader("Favoritas") }
                    items(favoriteApps, key = { "f-" + it.id }) { app ->
                        ApplicationCard(app, loader, running = app.id in running, favorite = true,
                            onClick = { if (app.id in running) sheetFor = app else launch(app) },
                            onLongClick = { haptics.longPress(); sheetFor = app })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-all") {
                    SectionHeader(if (q.isEmpty()) "Todas" else "Resultados",
                                  subtitle = if (q.isEmpty()) "${all.size} apps · mantén pulsada una para más opciones"
                                             else "${filtered.size} de ${all.size}")
                }
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyState(Icons.Outlined.SearchOff, "Sin resultados", "Ninguna app coincide con \"$q\".")
                    }
                }
                items(filtered, key = { "a-" + it.id }) { app ->
                    ApplicationCard(app, loader, running = app.id in running, favorite = app.id in favorites,
                        onClick = { if (app.id in running) sheetFor = app else launch(app) },
                        onLongClick = { haptics.longPress(); sheetFor = app })
                }
            }
        }
    }

    sheetFor?.let { app ->
        val appWindows = windowsOf(app, windows)
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, loader, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (appWindows.isNotEmpty()) "En ejecución · ${appWindows.size} ventana(s)" else "No está abierta",
                             style = MaterialTheme.typography.bodyMedium,
                             color = if (appWindows.isNotEmpty()) PcRemoteTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val fav = app.id in favorites
                    IconButton(onClick = { haptics.toggle(!fav); favorites = prefs.toggleFavorite(app.id) }) {
                        Icon(if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                             contentDescription = if (fav) "Quitar de favoritas" else "Añadir a favoritas",
                             tint = if (fav) PcRemoteTheme.extended.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { sheetFor = null; launch(app) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.RocketLaunch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (appWindows.isEmpty()) "Abrir" else "Abrir otra ventana")
                }
                appWindows.forEach { w ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(w.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { sheetFor = null; windowAction(w, "focus", "Ventana enfocada") }) {
                                    Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Enfocar")
                                }
                                OutlinedButton(onClick = { sheetFor = null; windowAction(w, "close", "Ventana cerrada") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Cerrar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApplicationCard(
    app: AppEntry, loader: IconLoader, running: Boolean, favorite: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val ext = PcRemoteTheme.extended
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Más opciones")
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = listOfNotNull(if (running) "En ejecución" else null, if (favorite) "Favorita" else null)
                    .joinToString(", ")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(app, loader, 52.dp)
            if (running) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)) {
                    StatusDot(ext.success, live = false, size = 6.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(app.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
             textAlign = TextAlign.Center, minLines = 2)
    }
}

@Composable
private fun AppIcon(app: AppEntry, loader: IconLoader, size: Dp) {
    // Asking only when the tile is composed = only for what is on screen.
    LaunchedEffect(app.id, loader.generation) { loader.request(app.id) }
    val icon = loader.icons[app.id]
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(size / 8))
            else Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun AppsSkeleton() {
    LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), contentPadding = PaddingValues(16.dp), userScrollEnabled = false) {
        items(12) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).skeleton(MaterialTheme.shapes.medium))
                Spacer(Modifier.height(8.dp))
                SkeletonLine(64.dp, 10.dp)
            }
        }
    }
}
