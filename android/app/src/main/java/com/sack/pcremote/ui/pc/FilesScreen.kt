package com.sack.pcremote.ui.pc

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// ══════════════════════════════════════════════════════════════
// Archivos del PC: carpetas del usuario y unidades; abrir en el PC,
// mostrar en el Explorador, bajar al móvil y subir desde el móvil
// (a la carpeta que estés viendo, o a Descargas).
// Trozos de 512 KB: el agente acepta mensajes de hasta 4 MB.
// ══════════════════════════════════════════════════════════════

private const val CHUNK = 512 * 1024
private const val TRANSFER_TIMEOUT_MS = 30_000L

private data class Transfer(val name: String, val upload: Boolean, val done: Long, val total: Long)

private fun iconFor(e: FileEntry): ImageVector = when {
    e.dir -> Icons.Outlined.Folder
    e.ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> Icons.Outlined.Image
    e.ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> Icons.Outlined.Movie
    e.ext in setOf("mp3", "flac", "wav", "ogg", "m4a") -> Icons.Outlined.MusicNote
    e.ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Outlined.FolderZip
    e.ext in setOf("pdf") -> Icons.Outlined.PictureAsPdf
    e.ext in setOf("exe", "msi", "bat", "cmd", "ps1") -> Icons.Outlined.Terminal
    e.ext in setOf("txt", "md", "log", "json", "xml", "csv", "ini") -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun placeIcon(icon: String?): ImageVector = when (icon) {
    "desktop" -> Icons.Outlined.DesktopWindows
    "documents" -> Icons.Outlined.Description
    "downloads" -> Icons.Outlined.Download
    "pictures" -> Icons.Outlined.Image
    "music" -> Icons.Outlined.MusicNote
    "videos" -> Icons.Outlined.Movie
    "home" -> Icons.Outlined.Home
    else -> Icons.Outlined.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: PcSession, back: () -> Unit) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = rememberTextClipboard()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    var path by rememberSaveable { mutableStateOf<String?>(null) }   // null = roots
    var roots by remember { mutableStateOf<FileRoots?>(null) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileEntry?>(null) }
    var transfer by remember { mutableStateOf<Transfer?>(null) }
    var transferJob by remember { mutableStateOf<Job?>(null) }
    var pendingDownload by remember { mutableStateOf<FileEntry?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, path, showHidden, reload) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        loading = true
        error = null
        val p = path
        if (p == null) {
            runCatching { vm.call("files", "roots", serializer = FileRoots.serializer()) }
                .onSuccess { roots = it }.onFailure { error = it }
        } else {
            runCatching {
                vm.call("files", "list", buildJsonObject { put("path", p); put("showHidden", showHidden) }, FolderListing.serializer(), 15_000)
            }.onSuccess { listing = it }.onFailure { error = it }
        }
        loading = false
    }

    // Back goes up a folder before leaving the screen.
    BackHandler(enabled = path != null) { path = listing?.takeIf { it.path == path }?.parent }

    fun go(p: String?) { haptics.tick(); listing = null; path = p }

    fun run(action: String, e: FileEntry, done: String) {
        scope.launch {
            runCatching { client.call("files", action, buildJsonObject { put("path", e.path) }) }
                .onSuccess { haptics.confirm(); vm.post(done) }
                .onFailure { haptics.reject(); vm.post(friendlyMessage(it)) }
        }
    }

    // ── download: PC → phone ──
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val e = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        if (uri == null) return@rememberLauncherForActivityResult
        transferJob = scope.launch {
            val total = e.size ?: 0
            transfer = Transfer(e.name, upload = false, done = 0, total = total)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                        var offset = 0L
                        while (isActive) {
                            val data = client.call("files", "read",
                                buildJsonObject { put("path", e.path); put("offset", offset); put("length", CHUNK) }, TRANSFER_TIMEOUT_MS)
                            val chunk = com.sack.pcremote.session.AgentJson.decodeFromJsonElement(FileChunk.serializer(), data!!)
                            out.write(Base64.decode(chunk.data, Base64.DEFAULT))
                            offset += chunk.length
                            transfer = Transfer(e.name, false, offset, chunk.total)
                            if (chunk.eof || chunk.length == 0) break
                        }
                    }
                }
            }
            transfer = null
            result.onSuccess { haptics.confirm(); vm.post("${e.name} guardado en el móvil") }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { haptics.reject(); vm.post("Descarga fallida: ${friendlyMessage(it)}") } }
        }
    }

    // ── upload: phone → PC ──
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "archivo") to (if (c.isNull(1)) 0L else c.getLong(1)) else null
        } ?: ("archivo" to 0L)
        val dir = path
        transferJob = scope.launch {
            transfer = Transfer(name, upload = true, done = 0, total = size)
            val uploadId = UUID.randomUUID().toString().replace("-", "")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(CHUNK)
                        var offset = 0L
                        var savedAt: String? = null
                        while (isActive) {
                            var n = 0
                            while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                            val done = n < CHUNK
                            val res = client.call("files", "upload", buildJsonObject {
                                put("uploadId", uploadId); put("name", name); put("offset", offset)
                                put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)); put("done", done)
                                if (dir != null) put("dir", dir)
                            }, TRANSFER_TIMEOUT_MS)
                            offset += n
                            transfer = Transfer(name, true, offset, maxOf(size, offset))
                            if (done) {
                                savedAt = (res as? kotlinx.serialization.json.JsonObject)?.get("path")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                break
                            }
                        }
                        savedAt
                    }
                }
            }
            transfer = null
            result.onSuccess { at -> haptics.confirm(); vm.post("Subido: ${at ?: name}"); reload++ }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { haptics.reject(); vm.post("Subida fallida: ${friendlyMessage(it)}") } }
        }
    }

    val title = if (path == null) "Archivos" else listing?.name?.ifEmpty { listing?.path } ?: "…"
    SubScreen(
        title,
        back = { if (path != null) go(listing?.takeIf { it.path == path }?.parent) else back() },
        subtitle = path,
        actions = {
            if (path != null) {
                IconButton(onClick = { showHidden = !showHidden }) {
                    Icon(if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        if (showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos")
                }
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Recargar") }
        },
        floating = {
            if (state == ConnectionState.CONNECTED && transfer == null && !(error is RequestException && (error as RequestException).code == "FEATURE_DISABLED")) {
                ExtendedFloatingActionButton(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Outlined.Upload, null) },
                    text = { Text(if (path == null) "Subir a Descargas" else "Subir aquí") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            transfer?.let { t -> TransferBar(t) { transferJob?.cancel(); transfer = null } }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            RequireConnection(vm) {
                val e = error
                when {
                    e is RequestException && e.code == "FEATURE_DISABLED" -> EmptyState(
                        Icons.Outlined.FolderOff, "Archivos desactivado",
                        "Actívalo en el PC: PC Remote en la bandeja → «Funciones y plugins» → Archivos.",
                    )
                    e != null -> ErrorState(AgentError.from(e).let { if (e is RequestException) AgentError("No se pudo abrir", friendlyMessage(e)) else it },
                        onRetry = { reload++ }, extra = { if (path != null) TextButton(onClick = { go(null) }) { Text("Ir al inicio") } })
                    path == null -> RootsList(roots, onOpen = { go(it) })
                    listing == null -> SkeletonList(8)
                    else -> {
                        val l = listing!!
                        if (l.entries.isEmpty()) EmptyState(Icons.Outlined.FolderOpen, "Carpeta vacía", "No hay nada aquí${if (!showHidden) " (los ocultos no se muestran)" else ""}.")
                        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                            items(l.entries, key = { it.path }) { f ->
                                ListItem(
                                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                                    supportingContent = {
                                        Text(listOfNotNull(f.size?.let { formatBytes(it) }, f.modified.takeIf { it > 0 }?.let { formatDate(it) }).joinToString(" · "))
                                    },
                                    leadingContent = {
                                        IconTile(iconFor(f), size = 40.dp,
                                            container = if (f.dir) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            content = if (f.dir) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    trailingContent = { if (f.dir) Icon(Icons.Outlined.ChevronRight, null) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.clickable { if (f.dir) go(f.path) else { haptics.tick(); selected = f } },
                                )
                            }
                            if (l.truncated) item { Text("Mostrando los primeros ${l.entries.size} elementos.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
    }

    selected?.let { f ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text(f.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(listOfNotNull(f.size?.let { formatBytes(it) }, formatDate(f.modified)).joinToString(" · ")) },
                    leadingContent = { IconTile(iconFor(f), size = 48.dp) },
                )
                HorizontalDivider()
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Abrir en el PC") { selected = null; run("open", f, "Abriendo ${f.name} en el PC") }
                SheetAction(Icons.Outlined.FolderOpen, "Mostrar en el Explorador") { selected = null; run("reveal", f, "Mostrado en el Explorador") }
                SheetAction(Icons.Outlined.Download, "Descargar al móvil", enabled = transfer == null) {
                    selected = null
                    pendingDownload = f
                    // The suggested name carries the extension; the picker infers the type from it.
                    saveLauncher.launch(f.name)
                }
                SheetAction(Icons.Outlined.ContentCopy, "Copiar ruta") {
                    selected = null; clipboard.copy(f.path); vm.post("Ruta copiada")
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun TransferBar(t: Transfer, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (t.upload) Icons.Outlined.Upload else Icons.Outlined.Download, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (t.upload) "Subiendo ${t.name}" else "Descargando ${t.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatBytes(t.done)}${if (t.total > 0) " de ${formatBytes(t.total)}" else ""}", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
            Spacer(Modifier.height(8.dp))
            if (t.total > 0) LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RootsList(roots: FileRoots?, onOpen: (String) -> Unit) {
    if (roots == null) { SkeletonList(8); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionHeader("Carpetas") }
        items(roots.places, key = { "p-" + it.path }) { p ->
            Card(onClick = { onOpen(p.path) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(p.path, style = MonoStyle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    leadingContent = { IconTile(placeIcon(p.icon)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
        item { SectionHeader("Unidades") }
        items(roots.drives, key = { "d-" + it.path }) { d ->
            Card(onClick = { if (d.ready) onOpen(d.path) }, enabled = d.ready, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    val used = if (d.totalBytes != null && d.freeBytes != null && d.totalBytes > 0) 1f - d.freeBytes.toFloat() / d.totalBytes else null
                    MetricBar(
                        listOfNotNull(d.name, d.label?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        used?.let { "${(it * 100).toInt()} %" } ?: if (d.ready) "" else "No disponible",
                        used,
                        icon = when (d.type) { "removable" -> Icons.Outlined.Usb; "network" -> Icons.Outlined.Cloud; "cdrom" -> Icons.Outlined.Album; else -> Icons.Outlined.Storage },
                        detail = if (d.freeBytes != null && d.totalBytes != null) "${formatBytes(d.freeBytes)} libres de ${formatBytes(d.totalBytes)}" else null,
                    )
                }
            }
        }
    }
}
