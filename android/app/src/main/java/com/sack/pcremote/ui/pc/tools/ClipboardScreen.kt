package com.sack.pcremote.ui.pc.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.*
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

// ══════════════════════════════════════════════════════════════
// Portapapeles del PC.
//
//   Arriba: lo que hay copiado ahora (texto, imagen o archivos).
//   Enviar al PC: texto o una imagen de la galería del móvil.
//   Historial: todo lo copiado en el PC mientras el agente está abierto
//   (plugin cliphistory), con miniaturas de las imágenes. Cada entrada se
//   puede copiar al móvil, guardar, compartir, volver a poner en el
//   portapapeles del PC o borrar.
//
// Nada se copia solo al móvil: Android avisa cada vez que una app escribe
// en el portapapeles, y hacerlo sin que el usuario lo pida sería molesto.
// ══════════════════════════════════════════════════════════════

private enum class ClipFilter(val label: String, val type: String?) {
    All("Todo", null), Text("Texto", "text"), Images("Imágenes", "image"), Files("Archivos", "files"),
}

private const val PAGE = 40
/** Longest side of images sent from the phone; the PC's clipboard does not need more. */
private const val SEND_MAX_SIDE = 2560

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(session: PcSession, onBack: () -> Unit) {
    val client = session.client
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    val snackbar = remember { SnackbarHostState() }
    val state by session.state.collectAsState()
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val phoneClipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val clipboardOn = plugins.let { session.isAvailable("clipboard") }
    val historyOn = plugins.let { session.isAvailable("cliphistory") }

    var current by remember { mutableStateOf<ClipboardState?>(null) }
    var items by remember { mutableStateOf<List<ClipHistoryItem>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var version by remember { mutableLongStateOf(-1L) }
    var filter by rememberSaveable { mutableStateOf(ClipFilter.All) }
    var outgoing by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ClipHistoryItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var sendingImage by remember { mutableStateOf(false) }

    suspend fun loadHistory(append: Boolean = false) {
        if (!historyOn) return
        val offset = if (append) items?.size ?: 0 else 0
        client.call("cliphistory", "list", ClipHistoryPage.serializer(), buildJsonObject {
            put("offset", offset); put("limit", PAGE); filter.type?.let { put("type", it) }
        }).onSuccess { page ->
            items = if (append) items.orEmpty() + page.items else page.items
            total = page.total
            version = page.version
        }
    }

    // Live: what is on the PC's clipboard, and a reload of the history when it changes.
    LaunchedEffect(client, clipboardOn) {
        if (!clipboardOn) return@LaunchedEffect
        client.stream("clipboard", "watch").collect { data ->
            val c = client.decode(ClipboardState.serializer(), data) ?: return@collect
            current = c
            if (c.historyVersion != version) loadHistory()
        }
    }
    LaunchedEffect(filter, historyOn, state) {
        if (state == ConnectionState.CONNECTED) loadHistory()
    }

    fun toPhoneText(text: String) {
        phoneClipboard.setPrimaryClip(ClipData.newPlainText("PC", text))
        haptics.confirm()
        scope.launch { snackbar.showSnackbar("Copiado en el móvil") }
    }

    fun sendText() {
        scope.launch {
            val r = client.call("clipboard", "set", JsonObject.serializer(), buildJsonObject { put("text", outgoing) })
            if (r.isSuccess) { haptics.confirm(); outgoing = "" } else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) "Copiado en el PC" else "No se pudo: ${r.exceptionOrNull()?.message}")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            sendingImage = true
            val encoded = withContext(Dispatchers.IO) { encodeForPc(ctx, uri) }
            if (encoded == null) {
                sendingImage = false; haptics.reject(); snackbar.showSnackbar("No se pudo leer la imagen"); return@launch
            }
            val r = client.call("clipboard", "setImage", JsonObject.serializer(),
                buildJsonObject { put("imageBase64", encoded) }, timeoutMs = 30_000)
            sendingImage = false
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) "Imagen copiada en el PC" else "No se pudo: ${r.exceptionOrNull()?.message}")
        }
    }

    PcScaffold(
        title = "Portapapeles",
        subtitle = creds.agentName,
        onBack = onBack,
        actions = {
            if (historyOn && !items.isNullOrEmpty()) IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "Borrar el historial")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!clipboardOn) {
            Box(Modifier.padding(padding)) { PluginOffState("El portapapeles", onRefresh = session::refreshPlugins) }
            return@PcScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "now") {
                SectionHeader("En el PC ahora")
                CurrentClipCard(current, onCopyText = {
                    scope.launch {
                        // The stream carries a 4096-char preview; fetch the full text to copy it.
                        val full = client.call("clipboard", "get", ClipboardText.serializer()).getOrNull()?.text
                        toPhoneText(full ?: current?.text.orEmpty())
                    }
                }, onOpenLatest = { items?.firstOrNull()?.let { selected = it } })
            }

            item(key = "send") {
                SectionHeader("Enviar al PC")
                OutlinedTextField(
                    value = outgoing, onValueChange = { outgoing = it }, minLines = 2, maxLines = 6,
                    placeholder = { Text("Escribe o pega aquí") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        outgoing = phoneClipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Pegar")
                    }
                    OutlinedButton(enabled = !sendingImage, onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        if (sendingImage) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Imagen")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = outgoing.isNotEmpty() && state == ConnectionState.CONNECTED, onClick = ::sendText) { Text("Enviar") }
                }
            }

            item(key = "h-history") {
                SectionHeader("Historial", subtitle = if (historyOn) "Lo que copias en el PC, solo en memoria" else null)
                if (historyOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClipFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { haptics.tick(); filter = f }, label = { Text(f.label) })
                    }
                }
            }

            val list = items
            when {
                !historyOn -> item(key = "off") { PluginOffState("El historial del portapapeles", onRefresh = session::refreshPlugins) }
                list == null -> item(key = "loading") { SkeletonList(rows = 4) }
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Outlined.ContentPaste, "Historial vacío",
                        if (filter == ClipFilter.All) "Copia algo en el PC (texto, una captura, archivos) y aparecerá aquí."
                        else "No hay elementos de este tipo.")
                }
                else -> {
                    items(list, key = { it.id }) { item ->
                        ClipHistoryRow(item, onClick = { haptics.tick(); selected = item })
                    }
                    if (list.size < total) item(key = "more") {
                        TextButton(onClick = { scope.launch { loadHistory(append = true) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cargar más (${total - list.size})")
                        }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        ClipDetailSheet(
            item = item,
            session = session,
            onDismiss = { selected = null },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            onChanged = { scope.launch { loadHistory() } },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "¿Borrar el historial?",
            message = "Se borra todo el historial del portapapeles guardado en el agente. Lo que hay copiado ahora en el PC no cambia.",
            confirmLabel = "Borrar",
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = {
                scope.launch {
                    client.call("cliphistory", "clear", JsonObject.serializer())
                    haptics.confirm()
                    loadHistory()
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun CurrentClipCard(c: ClipboardState?, onCopyText: () -> Unit, onOpenLatest: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            when {
                c == null -> { SkeletonLine(220.dp); Spacer(Modifier.height(8.dp)); SkeletonLine(140.dp) }
                c.type == "image" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(c.thumbBase64, 96.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Imagen", style = MaterialTheme.typography.titleSmall)
                        Text("${c.width} × ${c.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenLatest, contentPadding = PaddingValues(0.dp)) { Text("Ver y guardar") }
                    }
                }
                c.type == "files" -> {
                    Text("${c.fileCount} archivo(s)", style = MaterialTheme.typography.titleSmall)
                    c.files?.take(5)?.forEach { Text(it, style = MonoStyle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                }
                c.length == 0 -> Text("Vacío o con un formato que no se puede mostrar.", style = MaterialTheme.typography.bodyMedium,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    SelectionContainer {
                        Text(c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.length > c.text.length) Text("${c.length} caracteres", style = MaterialTheme.typography.labelSmall,
                                                       color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onCopyText) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Copiar en el móvil")
                    }
                }
            }
            if (c?.isPrivate == true) {
                Spacer(Modifier.height(8.dp))
                Text("Marcado como privado por la app que lo copió: no se guarda en el historial.",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ClipHistoryRow(item: ClipHistoryItem, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (item.type) {
                "image" -> Thumb(item.thumbBase64, 56.dp)
                else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small,
                                modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (item.type == "files") Icons.Outlined.FileCopy else Icons.AutoMirrored.Outlined.Notes, contentDescription = null,
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (item.type) {
                        "image" -> "Imagen · ${item.width} × ${item.height}"
                        "files" -> item.files?.joinToString(", ") ?: "${item.fileCount} archivo(s)"
                        else -> item.preview.orEmpty().replace('\n', ' ')
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(relativeTime(item.ts),
                                  when (item.type) { "image" -> formatBytes(item.sizeBytes); "text" -> "${item.length} caracteres"; else -> null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Thumb(b64: String?, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(b64) { b64?.let(::decodeImage) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.size(size)) {
        if (bmp != null) Image(bmp, contentDescription = "Miniatura", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, contentDescription = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipDetailSheet(
    item: ClipHistoryItem,
    session: PcSession,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val client = session.client
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    var full by remember { mutableStateOf<ClipFull?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        client.call("cliphistory", "get", ClipFull.serializer(), buildJsonObject { put("id", item.id) }, timeoutMs = 30_000)
            .onSuccess { f ->
                full = f
                f.pngBase64?.let { b64 ->
                    val bytes = withContext(Dispatchers.Default) { Base64.decode(b64, Base64.DEFAULT) }
                    imageBytes = bytes
                    image = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                }
            }
            .onFailure { error = it.message }
    }

    fun restore() {
        scope.launch {
            val r = client.call("cliphistory", "restore", JsonObject.serializer(), buildJsonObject { put("id", item.id) })
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            onMessage(if (r.isSuccess) "De nuevo en el portapapeles del PC" else "No se pudo: ${r.exceptionOrNull()?.message}")
            onDismiss()
        }
    }

    fun delete() {
        scope.launch {
            client.call("cliphistory", "delete", JsonObject.serializer(), buildJsonObject { put("id", item.id) })
            haptics.confirm()
            onChanged()
            onDismiss()
        }
    }

    val name = "PC-${item.id}.png"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(when (item.type) { "image" -> "Imagen"; "files" -> "Archivos"; else -> "Texto" }, style = MaterialTheme.typography.titleLarge)
            Text(relativeTime(item.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                full == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                item.type == "image" -> image?.let {
                    Image(it, contentDescription = "Imagen del portapapeles", contentScale = ContentScale.Fit,
                          modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium))
                }
                item.type == "files" -> Column {
                    full?.files?.forEach { Text(it, style = MonoStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis) }
                }
                else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    SelectionContainer {
                        Text(full?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                             modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (item.type == "text") {
                SheetButton(Icons.Outlined.ContentCopy, "Copiar en el móvil", enabled = full != null) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("PC", full?.text.orEmpty()))
                    haptics.confirm(); onMessage("Copiado en el móvil"); onDismiss()
                }
                SheetButton(Icons.Outlined.Share, "Compartir", enabled = full != null) {
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, full?.text.orEmpty()), "Compartir"))
                }
            }
            if (item.type == "image") {
                val bytes = imageBytes
                SheetButton(Icons.Outlined.ContentCopy, "Copiar en el móvil", enabled = bytes != null) {
                    scope.launch {
                        val uri = FileShare.toCache(ctx, name, bytes!!)
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Imagen del PC", uri))
                        haptics.confirm(); onMessage("Imagen copiada en el móvil"); onDismiss()
                    }
                }
                SheetButton(Icons.Outlined.Download, "Guardar en la galería", enabled = bytes != null) {
                    scope.launch {
                        val ok = FileShare.saveToDevice(ctx, name, bytes!!, "image/png")
                        if (ok) haptics.confirm() else haptics.reject()
                        onMessage(if (ok) "Guardada en Imágenes/PC Remote" else "Tu Android no permite guardarla; usa Compartir")
                        onDismiss()
                    }
                }
                SheetButton(Icons.Outlined.Share, "Compartir", enabled = bytes != null) {
                    scope.launch { FileShare.share(ctx, FileShare.toCache(ctx, name, bytes!!), "image/png") }
                }
            }
            SheetButton(Icons.Outlined.Replay, "Volver a copiar en el PC", onClick = ::restore)
            SheetButton(Icons.Outlined.Delete, "Borrar del historial", danger = true, onClick = ::delete)
        }
    }
}

@Composable
private fun SheetButton(icon: ImageVector, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = when { !enabled -> MaterialTheme.colorScheme.outline; danger -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface },
            leadingIconColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

private fun decodeImage(b64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/**
 * Reads an image picked on the phone and re-encodes it for the PC: at most
 * [SEND_MAX_SIDE] px on the long side, JPEG (PNG when it has transparency).
 */
private fun encodeForPc(ctx: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SEND_MAX_SIDE) sample *= 2
    val decoded = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scale = minOf(1f, SEND_MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    val out = ByteArrayOutputStream()
    if (bmp.hasAlpha()) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()
