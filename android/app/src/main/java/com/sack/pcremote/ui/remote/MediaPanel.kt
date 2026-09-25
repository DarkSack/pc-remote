package com.sack.pcremote.ui.remote

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.NowPlaying
import com.sack.pcremote.ui.components.rememberHaptics
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Multimedia: lo que suena en el PC (media.nowPlaying en stream), los
// controles de reproducción y el volumen del dispositivo de salida.
// ══════════════════════════════════════════════════════════════

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun MediaPanel(client: AgentClient, state: ConnectionState) {
    var now by remember { mutableStateOf(NowPlaying()) }
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    // While the user drags the slider, stream updates must not yank it back.
    var dragVolume by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        val sub = client.subscribe("media", "nowPlaying") { data ->
            val np = runCatching { json.decodeFromJsonElement(NowPlaying.serializer(), data) }.getOrNull() ?: return@subscribe
            if (np.trackChanged) {
                artwork = np.artworkBase64?.let { b64 ->
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            now = np
        }
        try { awaitCancellation() } finally { sub.cancel() }
    }

    // Send volume while dragging, at most every 120 ms. This used to be keyed on
    // dragVolume, which restarts the effect on every change: a debounce, not a
    // throttle, so the PC's volume only moved once the finger stopped.
    LaunchedEffect(client) {
        var lastSent: Int? = null
        while (isActive) {
            delay(120)
            val v = dragVolume?.toInt()
            if (v == null) { lastSent = null; continue }
            if (v != lastSent) {
                client.send("media", "volumeSet", buildJsonObject { put("volume", v) })
                lastSent = v
            }
        }
    }

    val haptics = rememberHaptics()
    fun action(name: String) { haptics.tick(); client.send("media", name) }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val art = artwork
                if (art != null) Image(art, contentDescription = "Carátula", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(72.dp))
            }

        if (now.active) {
            Text(now.title ?: "Sin título", style = MaterialTheme.typography.titleLarge,
                 textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { now.source ?: "" },
                 color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2)
        } else {
            Text("No hay nada reproduciéndose", style = MaterialTheme.typography.titleMedium)
            Text("Spotify, el navegador y la mayoría de reproductores aparecen aquí al darle a play.",
                 color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundControl(Icons.Filled.SkipPrevious, "Anterior", enabled = now.active) { action("previous") }
            RoundControl(
                if (now.status == "Playing") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Reproducir / pausar", enabled = now.active, big = true,
            ) { action("playPause") }
            RoundControl(Icons.Filled.SkipNext, "Siguiente", enabled = now.active) { action("next") }
        }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val muted = now.mute == true
            IconButton(onClick = {
                client.send("media", "volumeMute", buildJsonObject { put("mute", !muted) })
                now = now.copy(mute = !muted)
            }) {
                Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                     contentDescription = if (muted) "Quitar silencio" else "Silenciar",
                     tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = dragVolume ?: (now.volume ?: 0).toFloat(),
                onValueChange = { dragVolume = it },
                onValueChangeFinished = {
                    dragVolume?.let {
                        client.send("media", "volumeSet", buildJsonObject { put("volume", it.toInt()) })
                        // Keep the new value until the next stream tick confirms it (up to a
                        // second later); otherwise the slider jumps back to the old volume.
                        now = now.copy(volume = it.toInt())
                    }
                    dragVolume = null
                },
                valueRange = 0f..100f,
                enabled = now.volume != null,
                modifier = Modifier.weight(1f),
            )
            Text("${(dragVolume ?: (now.volume ?: 0).toFloat()).toInt()}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                 modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, enabled: Boolean, big: Boolean = false, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = if (big) IconButtonDefaults.filledIconButtonColors()
                 else IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = Modifier.size(if (big) 76.dp else 56.dp),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(if (big) 40.dp else 28.dp)) }
}
