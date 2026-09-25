package com.sack.pcremote.ui.pc

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.data.AppPrefs
import com.sack.pcremote.net.*
import com.sack.pcremote.session.AgentJson
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.extendedColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ══════════════════════════════════════════════════════════════
// Apps del PC en rejilla: icono real, nombre y si está abierta.
//   - La lista llega por applications.watch (instalar/desinstalar en
//     el PC se refleja sola).
//   - Iconos por lotes (appicons.get), solo de lo que se ve.
//   - "Abierta" = hay una ventana cuyo ejecutable/descripción coincide
//     (windows.list cada 4 s mientras la pantalla está visible).
//   - Toque → hoja inferior: Abrir / Traer al frente / Cerrar / Favorita.
// ══════════════════════════════════════════════════════════════

private const val FIRST_LOAD_TIMEOUT_MS = 30_000L
private const val ICON_BATCH = 40
private const val ICON_BATCH_DELAY_MS = 120L
private const val WINDOWS_POLL_MS = 4000L

/** Loads icons on demand, batching the ids of the cells that just appeared. */
private class IconLoader(private val client: AgentClient) {
    val icons = mutableStateMapOf<String, ImageBitmap?>()
    private val requested = HashSet<String>()
    private val queue = Channel<String>(Channel.UNLIMITED)
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
            delay(ICON_BATCH_DELAY_MS)
            val batch = mutableListOf(first)
            while (batch.size < ICON_BATCH) batch += queue.tryReceive().getOrNull() ?: break
            val map = runCatching {
                val data = client.call("appicons", "get", buildJsonObject { putJsonArray("ids") { batch.forEach { add(it) } } })
                AgentJson.decodeFromJsonElement(AppIcons.serializer(), data!!).icons
            }.getOrNull()
            if (map == null) { batch.forEach { requested.remove(it) }; continue }
            for (id in batch) {
                if (id !in map) { requested.remove(id); continue } // still being drawn on the PC
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

private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/** Windows that belong to [app], matched by exe name or file description. */
private fun windowsOf(app: AppEntry, windows: List<WindowInfo>): List<WindowInfo> {
    val n = norm(app.name)
    if (n.length < 2) return emptyList()
    return windows.filter { w ->
        val d = norm(w.description)
        val p = norm(w.process)
        d == n || p == n ||
            (d.length >= 4 && (n.startsWith(d) || d.startsWith(n))) ||
            (p.length >= 4 && n.startsWith(p))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: PcSession) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val creds by vm.creds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val settings = LocalSettings.current
    val prefs = remember(creds?.certFingerprintHex) { AppPrefs(ctx.applicationContext, creds?.certFingerprintHex ?: vm.deviceId) }
    val loader = remember(client) { IconLoader(client) }

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var windows by remember { mutableStateOf<List<WindowInfo>>(emptyList()) }
    var error by remember { mutableStateOf<AgentError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyRunning by rememberSaveable { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.favorites()) }
    var recents by remember { mutableStateOf(prefs.recents()) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var confirmClose by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(loader) { loader.run() }

    fun apply(data: kotlinx.serialization.json.JsonElement) {
        val list = runCatching { AgentJson.decodeFromJsonElement(AppList.serializer(), data) }.getOrNull() ?: return
        apps = list.applications.distinctBy { it.id }
        error = null
        if (list.applications.isNotEmpty()) {
            prefs.prune(list.applications.mapTo(HashSet()) { it.id })
            favorites = prefs.favorites()
            recents = prefs.recents()
        }
    }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        loader.resetPending()
        val sub = client.subscribe("applications", "watch", onError = { error = AgentError("No se pudo leer la lista", it.message ?: "") }) { apply(it) }
        try {
            if (apps == null && withTimeoutOrNull(FIRST_LOAD_TIMEOUT_MS) { while (apps == null) delay(200) } == null && apps == null) {
                error = AgentError("El PC no respondió", "No llegó la lista de apps. Prueba a recargar.")
            }
            awaitCancellation()
        } finally {
            sub.cancel()
        }
    }

    // Running state: which windows are open right now.
    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        while (true) {
            runCatching { vm.call("windows", "list", serializer = WindowList.serializer()) }
                .onSuccess { windows = it.windows }
            delay(WINDOWS_POLL_MS)
        }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            runCatching { client.call("applications", "list", buildJsonObject { put("refresh", true) }, FIRST_LOAD_TIMEOUT_MS) }
                .onSuccess { it?.let(::apply) }
                .onFailure { if (apps == null) error = AgentError.from(it) }
            refreshing = false
        }
    }

    fun launchApp(app: AppEntry) {
        haptics.confirm()
        scope.launch {
            runCatching { client.call("applications", "launch", buildJsonObject { put("id", app.id) }) }
                .onSuccess { recents = prefs.addRecent(app.id); vm.post("Abriendo ${app.name} en el PC") }
                .onFailure { haptics.reject(); vm.post("No se pudo abrir ${app.name}: ${friendlyMessage(it)}") }
        }
    }

    fun windowOp(app: AppEntry, action: String, done: String) {
        val targets = windowsOf(app, windows)
        if (targets.isEmpty()) { vm.post("${app.name} no tiene ventanas abiertas"); return }
        scope.launch {
            // Focus: the most relevant window only. Close: all of the app's windows.
            val list = if (action == "focus") listOf(targets.firstOrNull { it.foreground } ?: targets.first()) else targets
            val results = list.map { w -> runCatching { client.call("windows", action, buildJsonObject { put("hwnd", w.hwnd) }) } }
            if (results.all { it.isSuccess }) { haptics.confirm(); vm.post(done) }
            else { haptics.reject(); vm.post(friendlyMessage(results.first { it.isFailure }.exceptionOrNull()!!)) }
            if (action == "close") { delay(800); runCatching { vm.call("windows", "list", serializer = WindowList.serializer()) }.onSuccess { windows = it.windows } }
        }
    }

    val all = apps.orEmpty()
    val byId = remember(all) { all.associateBy { it.id } }
    val running = remember(all, windows) { all.filter { windowsOf(it, windows).isNotEmpty() }.mapTo(HashSet()) { it.id } }
    val q = query.trim()
    val filtered = remember(all, q, onlyRunning, running) {
        all.filter { (q.isEmpty() || it.name.contains(q, ignoreCase = true)) && (!onlyRunning || it.id in running) }
    }
    val favoriteApps = remember(byId, favorites) { favorites.mapNotNull { byId[it] }.sortedBy { it.name.lowercase() } }
    val recentApps = remember(byId, recents) { recents.mapNotNull { byId[it] } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Apps")
                        if (apps != null) Text("${all.size} instaladas · ${running.size} abiertas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh, enabled = !refreshing && state == ConnectionState.CONNECTED) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, contentDescription = "Volver a leer las apps del PC")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar app") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Borrar búsqueda") } }) else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !onlyRunning, onClick = { onlyRunning = false },
                        label = { Text("Todas") },
                    )
                    FilterChip(
                        selected = onlyRunning, onClick = { onlyRunning = true },
                        label = { Text("Abiertas (${running.size})") },
                        leadingIcon = { StatusDot(MaterialTheme.extendedColors.success, live = false, size = 4.dp) },
                    )
                }
            }
            RequireConnection(vm) {
                when {
                    apps == null && error == null -> SkeletonGrid()
                    apps == null -> ErrorState(error!!, onRetry = ::refresh)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val full: (LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
                        if (q.isEmpty() && !onlyRunning && recentApps.isNotEmpty()) {
                            item(key = "h-recent", span = full) { SectionHeader("Recientes", Modifier.padding(horizontal = 4.dp)) }
                            item(key = "recent-row", span = full) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    items(recentApps, key = { "r-" + it.id }) { app ->
                                        AssistChip(
                                            onClick = { launchApp(app) },
                                            label = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = { AppIcon(app, loader, 18.dp) },
                                            modifier = Modifier.widthIn(max = 180.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (q.isEmpty() && !onlyRunning && favoriteApps.isNotEmpty()) {
                            item(key = "h-fav", span = full) { SectionHeader("Favoritas", Modifier.padding(horizontal = 4.dp)) }
                            items(favoriteApps, key = { "f-" + it.id }) { app ->
                                ApplicationCard(app, loader, running = app.id in running, favorite = true) { haptics.tick(); selected = app }
                            }
                        }
                        item(key = "h-all", span = full) {
                            SectionHeader(if (q.isEmpty() && !onlyRunning) "Todas" else "${filtered.size} de ${all.size}", Modifier.padding(horizontal = 4.dp))
                        }
                        if (filtered.isEmpty()) {
                            item(key = "empty", span = full) {
                                if (onlyRunning && q.isEmpty()) EmptyState(Icons.Outlined.DesktopWindows, "Nada abierto", "Ninguna de tus apps tiene ventanas abiertas en el PC.")
                                else EmptyState(Icons.Outlined.SearchOff, "Sin resultados", "Ninguna app coincide con \"$q\".")
                            }
                        }
                        items(filtered, key = { it.id }) { app ->
                            ApplicationCard(app, loader, running = app.id in running, favorite = app.id in favorites) { haptics.tick(); selected = app }
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        val appWindows = windowsOf(app, windows)
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, loader, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (appWindows.isNotEmpty()) "Abierta · ${appWindows.size} ventana${if (appWindows.size == 1) "" else "s"}" else "Cerrada",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (appWindows.isNotEmpty()) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val fav = app.id in favorites
                    IconButton(onClick = { haptics.toggle(!fav); favorites = prefs.toggleFavorite(app.id) }) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (fav) "Quitar de favoritas" else "Añadir a favoritas",
                            tint = if (fav) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(Icons.AutoMirrored.Outlined.OpenInNew, "Abrir", modifier = Modifier.weight(1f),
                        onClick = { selected = null; launchApp(app) })
                    QuickActionButton(Icons.Outlined.PictureInPicture, "Al frente", modifier = Modifier.weight(1f), enabled = appWindows.isNotEmpty(),
                        onClick = { selected = null; windowOp(app, "focus", "${app.name} al frente") })
                    QuickActionButton(Icons.Outlined.Close, "Cerrar", tone = Tone.Danger, modifier = Modifier.weight(1f), enabled = appWindows.isNotEmpty(),
                        onClick = {
                            selected = null
                            if (settings.confirmDestructive) confirmClose = app else windowOp(app, "close", "Cerrando ${app.name}")
                        })
                }
                if (appWindows.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    appWindows.take(5).forEach { w ->
                        Text("• ${w.title.ifBlank { w.process }}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    confirmClose?.let { app ->
        ConfirmDialog(
            title = "¿Cerrar ${app.name}?",
            text = "Se pedirá a sus ventanas que se cierren. Si hay cambios sin guardar, la app preguntará en el PC.",
            confirmLabel = "Cerrar",
            icon = Icons.Outlined.Close,
            onConfirm = { windowOp(app, "close", "Cerrando ${app.name}") },
            onDismiss = { confirmClose = null },
        )
    }
}

@Composable
private fun AppIcon(app: AppEntry, loader: IconLoader, size: Dp) {
    val gen = loader.generation
    LaunchedEffect(app.id, gen) { loader.request(app.id) }
    val icon = loader.icons[app.id]
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.name.take(1).uppercase(), style = if (size >= 32.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ApplicationCard(app: AppEntry, loader: IconLoader, running: Boolean, favorite: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = app.name + (if (running) ", abierta" else "") + (if (favorite) ", favorita" else "")
        },
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Box(
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) { AppIcon(app, loader, 36.dp) }
                if (running) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(14.dp).clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.extendedColors.success),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(app.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 32.dp))
        }
    }
}

@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp), userScrollEnabled = false) {
        items(12) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SkeletonBox(Modifier.size(56.dp), 56.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBox(Modifier.width(60.dp), 10.dp)
            }
        }
    }
}
