package com.sack.pcremote.ui.pc.control

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.AgentClient
import com.sack.pcremote.ui.components.QuickActionButton
import com.sack.pcremote.ui.components.SectionHeader
import com.sack.pcremote.ui.components.haptics
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class Shortcut(val label: String, val keys: String, val icon: ImageVector)

private val GROUPS = listOf(
    "Windows" to listOf(
        Shortcut("Escritorio", "win+d", Icons.Outlined.DesktopWindows),
        Shortcut("Cambiar ventana", "alt+tab", Icons.Outlined.Tab),
        Shortcut("Explorador", "win+e", Icons.Outlined.FolderOpen),
        Shortcut("Ejecutar", "win+r", Icons.Outlined.PlayCircleOutline),
        Shortcut("Configuración", "win+i", Icons.Outlined.Settings),
        Shortcut("Captura", "win+shift+s", Icons.Outlined.Screenshot),
        Shortcut("Administrador de tareas", "ctrl+shift+esc", Icons.Outlined.Monitor),
        Shortcut("Cerrar ventana", "alt+f4", Icons.Outlined.Close),
    ),
    "Edición" to listOf(
        Shortcut("Copiar", "ctrl+c", Icons.Outlined.ContentCopy),
        Shortcut("Pegar", "ctrl+v", Icons.Outlined.ContentPaste),
        Shortcut("Cortar", "ctrl+x", Icons.Outlined.ContentCut),
        Shortcut("Seleccionar todo", "ctrl+a", Icons.Outlined.SelectAll),
        Shortcut("Deshacer", "ctrl+z", Icons.AutoMirrored.Outlined.Undo),
        Shortcut("Rehacer", "ctrl+y", Icons.AutoMirrored.Outlined.Redo),
        Shortcut("Guardar", "ctrl+s", Icons.Outlined.Save),
        Shortcut("Buscar", "ctrl+f", Icons.Outlined.Search),
    ),
    "Navegador" to listOf(
        Shortcut("Nueva pestaña", "ctrl+t", Icons.Outlined.AddBox),
        Shortcut("Cerrar pestaña", "ctrl+w", Icons.Outlined.DisabledByDefault),
        Shortcut("Reabrir pestaña", "ctrl+shift+t", Icons.Outlined.History),
        Shortcut("Recargar", "f5", Icons.Outlined.Refresh),
        Shortcut("Pantalla completa", "f11", Icons.Outlined.Fullscreen),
        Shortcut("Acercar", "ctrl+add", Icons.Outlined.ZoomIn),
        Shortcut("Alejar", "ctrl+subtract", Icons.Outlined.ZoomOut),
        Shortcut("Atrás", "alt+left", Icons.AutoMirrored.Outlined.ArrowBack),
    ),
    "Sonido" to listOf(
        Shortcut("Bajar volumen", "volumedown", Icons.AutoMirrored.Outlined.VolumeDown),
        Shortcut("Subir volumen", "volumeup", Icons.AutoMirrored.Outlined.VolumeUp),
        Shortcut("Silenciar", "volumemute", Icons.AutoMirrored.Outlined.VolumeOff),
        Shortcut("Reproducir / pausa", "mediaplaypause", Icons.Outlined.PlayArrow),
    ),
)

@Composable
fun ShortcutsPanel(client: AgentClient) {
    val haptics = haptics()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 80.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        GROUPS.forEach { (title, shortcuts) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") { SectionHeader(title) }
            items(shortcuts, key = { it.keys }) { s ->
                QuickActionButton(s.icon, s.label, onClick = {
                    haptics.key()
                    client.send("input", "keyPress", buildJsonObject { put("keys", s.keys) })
                })
            }
        }
    }
}
