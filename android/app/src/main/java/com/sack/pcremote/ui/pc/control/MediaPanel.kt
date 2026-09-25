package com.sack.pcremote.ui.pc.control

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.NowPlaying
import com.sack.pcremote.ui.components.haptics
import com.sack.pcremote.ui.theme.NumericStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Multimedia: lo que suena en el PC (media.nowPlaying en stream), los
// controles de reproducción y el volumen del dispositivo de salida.
// ══════════════════════════════════════════════════════════════

@Composable
fun MediaPanel(client: AgentClient, state: ConnectionState) {
    var now by remember { mutableStateOf(NowPlaying()) }
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    // While the user drags the slider, stream updates must not yank it back.
    var dragVolume by remember { mutableStateOf<Float?>(null) }
    val haptics = haptics()

    LaunchedEffect(client) {
        client.stream("media", "nowPlaying").collect { data ->
            val np = client.decode(NowPlaying.serializer(), data) ?: return@collect
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
    }

    // Send volume while dragging, at most every 120 ms (a throttle, not a debounce:
    // the PC's volume follows the finger).
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

    fun action(name: String) { haptics.key(); client.send("media", name) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MediaCard(now, artwork, state == ConnectionState.CONNECTED, onAction = ::action)
        Spacer(Modifier.height(16.dp))

        // Volume
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val muted = now.mute == true
                IconButton(onClick = {
                    haptics.toggle(!muted)
                    client.send("media", "volumeMute", buildJsonObject { put("mute", !muted) })
                    now = now.copy(mute = !muted)
                }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                         contentDescription = if (muted) "Quitar silencio" else "Silenciar",
                         tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                val shown = dragVolume ?: (now.volume ?: 0).toFloat()
                Slider(
                    value = shown,
                    onValueChange = { dragVolume = it },
                    onValueChangeFinished = {
                        dragVolume?.let {
                            client.send("media", "volumeSet", buildJsonObject { put("volume", it.toInt()) })
                            // Keep the new value until the next stream tick confirms it.
                            now = now.copy(volume = it.toInt())
                        }
                        dragVolume = null
                    },
                    valueRange = 0f..100f,
                    enabled = now.volume != null,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Volumen del PC" },
                )
                Text("${shown.toInt()}", style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
                     modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Square, rounded artwork; title and artist; previous / play-pause / next. */
@Composable
fun MediaCard(now: NowPlaying, artwork: ImageBitmap?, enabled: Boolean, onAction: (String) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(artwork, label = "artwork") { art ->
                    if (art != null) Image(art, contentDescription = "Carátula", contentScale = ContentScale.Crop,
                                           modifier = Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null,
                             tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(72.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (now.active) {
                Text(now.title ?: "Sin título", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { now.source ?: "" },
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Nada sonando", style = MaterialTheme.typography.titleLarge)
                Text("Spotify, el navegador y la mayoría de reproductores aparecen aquí al darle a play.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            val playing = now.status == "Playing"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoundControl(Icons.Filled.SkipPrevious, "Anterior", enabled && now.active) { onAction("previous") }
                RoundControl(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pausar" else "Reproducir",
                    enabled && now.active, big = true,
                    state = if (playing) "Reproduciendo" else "En pausa",
                ) { onAction("playPause") }
                RoundControl(Icons.Filled.SkipNext, "Siguiente", enabled && now.active) { onAction("next") }
            }
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, enabled: Boolean, big: Boolean = false, state: String? = null, onClick: () -> Unit) {
    val size = if (big) 76.dp else 56.dp
    if (big) {
        FilledIconButton(onClick = onClick, enabled = enabled, shape = CircleShape,
                         modifier = Modifier.size(size).semantics { if (state != null) stateDescription = state }) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(40.dp))
        }
    } else {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}
