package com.sack.pcremote.ui.pc.apps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.WindowInfo
import com.sack.pcremote.net.WindowList
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Open windows on the PC: focus, minimise, maximise, close. */
@Composable
fun WindowsPanel(session: PcSession, snackbar: SnackbarHostState) {
    val client = session.client
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    var windows by remember { mutableStateOf<List<WindowInfo>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, tick) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        while (isActive) {
            client.call("windows", "list", WindowList.serializer())
                .onSuccess { windows = it.windows; error = null }
                .onFailure { if (windows == null) error = it }
            delay(4_000)
        }
    }

    fun act(w: WindowInfo, action: String, done: String) {
        haptics.tick()
        scope.launch {
            val r = client.call("windows", action, JsonObject.serializer(), buildJsonObject { put("hwnd", w.hwnd) })
            if (r.isSuccess) haptics.confirm() else haptics.reject()
            snackbar.showSnackbar(if (r.isSuccess) done else "No se pudo: ${r.exceptionOrNull()?.message}")
            tick++
        }
    }

    val list = windows
    when {
        list == null && error == null -> SkeletonList()
        list == null -> ErrorState("No se pudieron leer las ventanas", error?.message ?: "", onRetry = { tick++ })
        list.isEmpty() -> EmptyState(Icons.Outlined.WebAsset, "No hay ventanas abiertas", "Cuando abras algo en el PC aparecerá aquí.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp),
                           verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.hwnd }) { w ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(w.process, if (w.foreground) "en primer plano" else null,
                                                  if (w.minimized) "minimizada" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (w.foreground) PcRemoteTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row {
                            WindowAction(Icons.Outlined.CenterFocusStrong, "Enfocar") { act(w, "focus", "Ventana enfocada") }
                            WindowAction(Icons.Outlined.Minimize, "Minimizar") { act(w, "minimize", "Ventana minimizada") }
                            WindowAction(Icons.Outlined.CropSquare, "Maximizar") { act(w, "maximize", "Ventana maximizada") }
                            Spacer(Modifier.weight(1f))
                            WindowAction(Icons.Outlined.Close, "Cerrar", destructive = true) { act(w, "close", "Ventana cerrada") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label,
             tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
