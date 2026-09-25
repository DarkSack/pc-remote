package com.sack.pcremote.ui.pc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.*
import com.sack.pcremote.session.AgentJson
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

// ══════════════════════════════════════════════════════════════
// Portapapeles del PC: todo el historial (texto, imágenes y archivos
// copiados), en vivo. Cada entrada se puede ver completa, copiar al
// móvil (o guardar, si es imagen), volver a poner en el PC o borrar.
// Abajo: enviar texto o una imagen del móvil al portapapeles del PC.
// ══════════════════════════════════════════════════════════════

/** The agent refuses images over 3 MB. */
private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

private fun decodeB64Image(b64: String?): ImageBitmap? = b64?.let {
    runCatching {
        val bytes = Base64.decode(it, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(vm: PcSession, back: () -> Unit) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = rememberTextClipboard()
    val haptics = rememberHaptics()
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()

    val items = remember { mutableStateListOf<ClipItem>() }
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<RequestException?>(null) }
    var selected by remember { mutableStateOf<ClipItem?>(null) }
    var full by remember { mutableStateOf<ClipFull?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        val sub = client.subscribe("clipboard", "historyWatch", onError = { error = it; loaded = true }) { data ->
            val o = runCatching { data.jsonObject }.getOrNull() ?: return@subscribe
            when (o["op"]?.jsonPrimitive?.contentOrNull) {
                "snapshot" -> {
                    val list = runCatching { AgentJson.decodeFromJsonElement(ListSerializer(ClipItem.serializer()), o["items"]!!) }.getOrDefault(emptyList())
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                    items.clear(); items.addAll(list); loaded = true; error = null
                }
                "add" -> runCatching { AgentJson.decodeFromJsonElement(ClipItem.serializer(), o["item"]!!) }.getOrNull()?.let { item ->
                    items.removeAll { it.id == item.id }
                    items.add(0, item)
                }
                "remove" -> o["id"]?.jsonPrimitive?.contentOrNull?.let { id -> items.removeAll { it.id == id } }
                "clear" -> items.clear()
            }
        }
        try { awaitCancellation() } finally { sub.cancel() }
    }

    fun act(action: String, item: ClipItem, done: String) {
        scope.launch {
            runCatching { client.call("clipboard", action, buildJsonObject { put("id", item.id) }) }
                .onSuccess { haptics.confirm(); vm.post(done) }
                .onFailure { haptics.reject(); vm.post(friendlyMessage(it)) }
        }
    }

    fun open(item: ClipItem) {
        haptics.tick()
        selected = item
        full = null
        scope.launch {
            runCatching { vm.call("clipboard", "historyGet", buildJsonObject { put("id", item.id) }, ClipFull.serializer(), 15_000) }
                .onSuccess { full = it }
                .onFailure { vm.post(friendlyMessage(it)); selected = null }
        }
    }

    fun sendText() {
        val t = text
        if (t.isEmpty()) return
        sending = true
        scope.launch {
            runCatching { client.call("clipboard", "set", buildJsonObject { put("text", t) }) }
                .onSuccess { haptics.confirm(); text = ""; vm.post("Copiado en el PC") }
                .onFailure { haptics.reject(); vm.post(friendlyMessage(it)) }
            sending = false
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.Default) { encodeForPc(ctx, uri) }
                client.call("clipboard", "setImage", buildJsonObject { put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)) }, 20_000)
            }.onSuccess { haptics.confirm(); vm.post("Imagen copiada en el PC") }
             .onFailure { haptics.reject(); vm.post("No se pudo enviar la imagen: ${friendlyMessage(it)}") }
            sending = false
        }
    }

    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        val bytes = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } } }
                .onSuccess { haptics.confirm(); vm.post("Imagen guardada en el móvil") }
                .onFailure { vm.post("No se pudo guardar: ${it.message}") }
        }
    }

    SubScreen(
        "Portapapeles", back,
        subtitle = if (loaded) "${items.size} en el historial del PC" else null,
        actions = {
            IconButton(onClick = { if (settings.confirmDestructive) confirmClear = true else scope.launch { runCatching { client.call("clipboard", "historyClear") } } },
                enabled = items.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, "Vaciar historial") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f)) {
                RequireConnection(vm) {
                    when {
                        error != null && items.isEmpty() -> ErrorState(AgentError("No se pudo leer el historial", friendlyMessage(error!!)))
                        !loaded -> SkeletonList(6)
                        !enabled -> EmptyState(Icons.Outlined.ContentPasteOff, "Historial desactivado", "El agente del PC tiene el historial del portapapeles apagado (Clipboard:HistorySize = 0).")
                        items.isEmpty() -> EmptyState(Icons.Outlined.ContentPaste, "Nada copiado todavía",
                            "Copia algo en el PC (texto, una captura, archivos) y aparecerá aquí al instante.")
                        else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items, key = { it.id }) { item ->
                                val thumb = remember(item.id) { decodeB64Image(item.thumbnail) }
                                ClipCard(item, thumb, onClick = { open(item) },
                                    onRestore = { act("historyRestore", item, "Copiado de nuevo en el PC") })
                            }
                        }
                    }
                }
            }
            // Phone → PC
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !sending && state == ConnectionState.CONNECTED) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "Enviar una imagen al portapapeles del PC")
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Texto para el PC") },
                        maxLines = 4,
                        trailingIcon = {
                            if (text.isEmpty()) IconButton(onClick = { clipboard.paste()?.let { text = it } }) {
                                Icon(Icons.Outlined.ContentPasteGo, "Pegar del móvil")
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = ::sendText, enabled = text.isNotEmpty() && !sending && state == ConnectionState.CONNECTED, modifier = Modifier.size(52.dp)) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Outlined.Send, "Copiar en el PC")
                    }
                }
            }
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null; full = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(kindLabel(item), style = MaterialTheme.typography.titleMedium)
                Text(formatDate(item.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val f = full
                Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    when {
                        f == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                        f.kind == "image" -> {
                            val img = remember(f.id) { decodeB64Image(f.png) }
                            if (img != null) Image(img, "Imagen copiada, ${f.width}×${f.height}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        }
                        else -> SelectionContainer {
                            Text(f.text.orEmpty(), style = if (f.kind == "files") MonoStyle else MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.kind == "image") {
                        QuickActionButton(Icons.Outlined.SaveAlt, "Guardar", modifier = Modifier.weight(1f), enabled = f?.png != null, onClick = {
                            pendingSave = Base64.decode(f!!.png, Base64.DEFAULT)
                            saveImage.launch("portapapeles-${item.ts}.png")
                        })
                    } else {
                        QuickActionButton(Icons.Outlined.ContentCopy, "Al móvil", modifier = Modifier.weight(1f), enabled = f?.text != null, onClick = {
                            clipboard.copy(f!!.text!!); haptics.confirm(); vm.post("Copiado en el móvil")
                        })
                    }
                    QuickActionButton(Icons.Outlined.ContentPasteGo, "Al PC", modifier = Modifier.weight(1f), onClick = {
                        selected = null; act("historyRestore", item, "Copiado de nuevo en el PC")
                    })
                    QuickActionButton(Icons.Outlined.Delete, "Borrar", tone = Tone.Danger, modifier = Modifier.weight(1f), onClick = {
                        selected = null; act("historyDelete", item, "Borrado del historial")
                    })
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "¿Vaciar el historial?",
            text = "Se borra el historial del portapapeles que guarda PC Remote (no lo que está copiado ahora mismo).",
            confirmLabel = "Vaciar",
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = { scope.launch { runCatching { client.call("clipboard", "historyClear") }.onFailure { vm.post(friendlyMessage(it)) } } },
            onDismiss = { confirmClear = false },
        )
    }
}

private fun kindLabel(item: ClipItem) = when (item.kind) {
    "image" -> "Imagen · ${item.width}×${item.height}"
    "files" -> "Archivos · ${item.files?.size ?: 0}"
    else -> "Texto · ${item.length} caracteres"
}

@Composable
private fun ClipCard(item: ClipItem, thumb: ImageBitmap?, onClick: () -> Unit, onRestore: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                item.kind == "image" && thumb != null -> Image(thumb, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
                item.kind == "image" -> IconTile(Icons.Outlined.Image, size = 56.dp)
                item.kind == "files" -> IconTile(Icons.Outlined.FileCopy, size = 40.dp,
                    container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                else -> IconTile(Icons.AutoMirrored.Outlined.Notes, size = 40.dp,
                    container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when (item.kind) {
                    "image" -> Text("Imagen ${item.width}×${item.height}", style = MaterialTheme.typography.bodyLarge)
                    "files" -> Text(item.files.orEmpty().joinToString(", "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else -> Text(item.preview.orEmpty().trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(formatAgo(item.ts), if (item.kind == "image") formatBytes(item.bytes.toLong()) else if (item.kind == "text") "${item.length} car." else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) { Icon(Icons.Outlined.ContentPasteGo, "Volver a copiar en el PC") }
        }
    }
}

/** PNG when it fits in 3 MB, otherwise JPEG, scaling down large photos. */
private fun encodeForPc(ctx: android.content.Context, uri: Uri): ByteArray {
    val src: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
    }
    var bmp = src
    var maxSide = 3840
    while (true) {
        val side = maxOf(bmp.width, bmp.height)
        if (side > maxSide) {
            val k = maxSide.toFloat() / side
            bmp = Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
        }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= MAX_IMAGE_BYTES) return png
        val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        if (jpg.size <= MAX_IMAGE_BYTES) return jpg
        maxSide = (maxOf(bmp.width, bmp.height) * 0.75f).toInt()
    }
}
