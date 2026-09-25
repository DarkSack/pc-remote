package com.sack.pcremote.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files that come from the PC (a downloaded file, a clipboard image): kept in the
 * app's cache and handed to other apps through FileProvider, or saved to the
 * phone's Downloads / Pictures.
 */
object FileShare {

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Writes [bytes] to the cache and returns a shareable content:// URI. */
    suspend fun toCache(context: Context, name: String, bytes: ByteArray): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Only the last path segment: a name from the PC must not escape the folder.
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "archivo" }
        val file = File(dir, safe)
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun open(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Abrir con"))
    }

    fun share(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Compartir"))
    }

    /**
     * Saves into the public Downloads (or Pictures for images) folder. Android 10+
     * only (MediaStore, no storage permission); returns false on older versions,
     * where the caller offers "Compartir" instead.
     */
    suspend fun saveToDevice(context: Context, name: String, bytes: ByteArray, mime: String = mimeOf(name)): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
            val image = mime.startsWith("image/")
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/PC Remote")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return@withContext false
            runCatching {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.onFailure { resolver.delete(uri, null, null) }.isSuccess
        }
}
