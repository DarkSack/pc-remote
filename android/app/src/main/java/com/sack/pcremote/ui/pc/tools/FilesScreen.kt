package com.sack.pcremote.ui.pc.tools

import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Archivos del PC: carpetas conocidas y unidades, navegación por carpetas
// con "migas de pan", y por archivo: abrir en el PC, mostrar en el
// Explorador o bajarlo al móvil (≤ 10 MB) para abrirlo, compartirlo o
// guardarlo en Descargas. Deliberadamente simple: no es un gestor de
// archivos completo.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(session: PcSession, onBack: () -> Unit) {
    val client = session.client
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val state by session.state.collectAsState()
    val plugins by session.plugins.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val available = plugins.let { session.isAvailable("files") }

    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var roots by remember { mutableStateOf<FileRoots?>(null) }
    var listing by remember { mutableStateOf<FileListing?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileEntry?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(path, showHidden, state, reload, available) {
        if (state != ConnectionState.CONNECTED || !available) return@LaunchedEffect
        loading = true
        error = null
        val p = path
        if (p == null) {
            client.call("files", "roots", FileRoots.serializer()).onSuccess { roots = it }.onFailure { error = it }
        } else {
            client.call("files", "list", FileListing.serializer(), buildJsonObject { put("path", p); put("hidden", showHidden) })
                .onSuccess { listing = it }.onFailure { error = it }
        }
        loading = false
    }

    BackHandler(enabled = path != null) { path = listing?.parent?.takeIf { path != null && it != path } }

    fun act(action: String, entry: FileEntry, done: String) {
        scope.launch {
            val r = client.call("files", action, JsonObject.serializer(), buildJsonObject { put("path", entry.path) })
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) done else r.exceptionOrNull()?.message ?: "No se pudo")
        }
    }

    fun download(entry: FileEntry, then: suspend (ByteArray) -> Unit) {
        scope.launch {
            downloading = true
            val r = client.call("files", "read", FileContent.serializer(), buildJsonObject { put("path", entry.path) }, timeoutMs = 60_000)
            downloading = false
            r.onSuccess { then(Base64.decode(it.base64, Base64.DEFAULT)) }
             .onFailure { haptics.reject(); snackbar.showSnackbar(it.message ?: "No se pudo descargar") }
        }
    }

    PcScaffold(
        title = "Archivos",
        subtitle = path ?: "Este PC",
        onBack = { if (path != null) path = listing?.parent?.takeIf { it != path } else onBack() },
        actions = {
            if (path != null) {
                IconButton(onClick = { haptics.toggle(!showHidden); showHidden = !showHidden }) {
                    Icon(if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                         contentDescription = if (showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos")
                }
                IconButton(onClick = { path = null }) { Icon(Icons.Outlined.Computer, contentDescription = "Este PC") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!available) { PluginOffState("Archivos", onRefresh = session::refreshPlugins); return@Column }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))

            val p = path
            if (p != null) Breadcrumb(p) { path = it }

            when {
                error != null -> ErrorState("No se pudo abrir", error?.message ?: "", onRetry = { reload++ })
                p == null -> roots?.let { RootsList(it) { dir -> haptics.tick(); path = dir } } ?: SkeletonList()
                else -> {
                    val l = listing
                    when {
                        l == null || l.path != p && loading -> SkeletonList()
                        l.entries.isEmpty() -> EmptyState(Icons.Outlined.FolderOff, "Carpeta vacía", "No hay nada que mostrar aquí.")
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(l.entries, key = { it.path }) { e ->
                                ListItem(
                                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                                    supportingContent = {
                                        Text(listOfNotNull(e.size?.let(::formatBytes), relativeTime(e.modified)).joinToString(" · "),
                                             style = MaterialTheme.typography.bodySmall)
                                    },
                                    leadingContent = {
                                        Icon(iconFor(e), contentDescription = if (e.dir) "Carpeta" else "Archivo",
                                             tint = if (e.dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    trailingContent = if (e.dir) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) else null,
                                    modifier = Modifier.clickable {
                                        haptics.tick()
                                        if (e.dir) path = e.path else selected = e
                                    },
                                )
                            }
                            if (l.truncated) item {
                                Text("Se muestran los primeros 2000 elementos.", style = MaterialTheme.typography.bodySmall,
                                     modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(e), contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(e.size?.let(::formatBytes), relativeTime(e.modified)).joinToString(" · "),
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SheetAction(Icons.Outlined.OpenInBrowser, "Abrir en el PC") { selected = null; act("open", e, "Abriendo ${e.name} en el PC") }
                SheetAction(Icons.Outlined.FolderOpen, "Mostrar en el Explorador") { selected = null; act("reveal", e, "Mostrado en el Explorador") }
                val small = (e.size ?: 0) <= 10L * 1024 * 1024
                if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                SheetAction(Icons.Outlined.PhoneAndroid, "Abrir en el móvil", enabled = small && !downloading) {
                    download(e) { bytes ->
                        val uri = FileShare.toCache(ctx, e.name, bytes)
                        selected = null
                        runCatching { FileShare.open(ctx, uri, FileShare.mimeOf(e.name)) }
                            .onFailure { Toast.makeText(ctx, "Ninguna app puede abrirlo", Toast.LENGTH_SHORT).show() }
                    }
                }
                SheetAction(Icons.Outlined.Share, "Compartir", enabled = small && !downloading) {
                    download(e) { bytes ->
                        val uri = FileShare.toCache(ctx, e.name, bytes)
                        selected = null
                        FileShare.share(ctx, uri, FileShare.mimeOf(e.name))
                    }
                }
                SheetAction(Icons.Outlined.Download, "Guardar en el móvil", enabled = small && !downloading) {
                    download(e) { bytes ->
                        val ok = FileShare.saveToDevice(ctx, e.name, bytes)
                        selected = null
                        if (ok) haptics.confirm()
                        snackbar.showSnackbar(if (ok) "Guardado en Descargas/PC Remote" else "Tu Android no permite guardarlo; usa Compartir")
                    }
                }
                if (!small) Text("Pesa más de 10 MB: solo se puede abrir en el PC.", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    // "C:\Users\Sack\Documents" → C:\, Users, Sack, Documents
    val parts = path.trimEnd('\\').split('\\').filter { it.isNotEmpty() }
    val targets = parts.runningReduce { acc, s -> "$acc\\$s" }.map { if (it.endsWith(':')) "$it\\" else it }
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(parts) { i, name ->
            if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp))
            TextButton(onClick = { onNavigate(targets[i]) }, enabled = i < parts.lastIndex) {
                Text(name, style = MonoStyle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RootsList(roots: FileRoots, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item { SectionHeader("Carpetas", Modifier.padding(horizontal = 16.dp)) }
        items(roots.folders, key = { it.path }) { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = { Text(f.path, style = MonoStyle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                leadingContent = { Icon(folderIcon(f.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onOpen(f.path) },
            )
        }
        item { SectionHeader("Unidades", Modifier.padding(horizontal = 16.dp)) }
        items(roots.drives, key = { it.path }) { d ->
            val total = d.totalBytes ?: 0
            val free = d.freeBytes ?: 0
            ListItem(
                headlineContent = { Text(d.name) },
                supportingContent = {
                    if (total > 0) MetricBar(
                        label = "${formatBytes(free)} libres de ${formatBytes(total)}",
                        fraction = ((total - free).toFloat() / total),
                        valueText = "${((total - free) * 100 / total)}%",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                leadingContent = {
                    Icon(if (d.kind == "removable") Icons.Outlined.Usb else Icons.Outlined.Storage, contentDescription = null,
                         tint = MaterialTheme.colorScheme.secondary)
                },
                modifier = Modifier.clickable { onOpen(d.path) },
            )
        }
    }
}

private fun folderIcon(kind: String): ImageVector = when (kind) {
    "desktop" -> Icons.Outlined.DesktopWindows
    "documents" -> Icons.Outlined.Description
    "downloads" -> Icons.Outlined.Download
    "pictures" -> Icons.Outlined.Image
    "music" -> Icons.Outlined.LibraryMusic
    "videos" -> Icons.Outlined.VideoLibrary
    "home" -> Icons.Outlined.Home
    else -> Icons.Outlined.Folder
}

private fun iconFor(e: FileEntry): ImageVector = if (e.dir) Icons.Outlined.Folder else when (e.ext) {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".heic", ".svg" -> Icons.Outlined.Image
    ".mp4", ".mkv", ".avi", ".mov", ".webm" -> Icons.Outlined.Movie
    ".mp3", ".flac", ".wav", ".ogg", ".m4a" -> Icons.Outlined.AudioFile
    ".pdf" -> Icons.Outlined.PictureAsPdf
    ".zip", ".rar", ".7z", ".tar", ".gz" -> Icons.Outlined.FolderZip
    ".txt", ".md", ".log", ".json", ".xml", ".csv", ".ini", ".yml", ".yaml" -> Icons.AutoMirrored.Outlined.Article
    ".doc", ".docx", ".odt", ".rtf" -> Icons.Outlined.Description
    ".xls", ".xlsx", ".ods" -> Icons.Outlined.TableChart
    ".exe", ".msi", ".bat", ".cmd", ".ps1" -> Icons.Outlined.Terminal
    ".kt", ".cs", ".java", ".py", ".js", ".ts", ".c", ".cpp", ".h", ".rs", ".go" -> Icons.Outlined.Code
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
