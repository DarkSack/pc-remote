package com.sack.pcremote.ui.pc.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sack.pcremote.net.ActivityEvent
import com.sack.pcremote.net.ActivityList
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.NumericStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Actividad: línea de tiempo de lo que pasó en el PC — conexiones,
// comandos (apagar, abrir apps…) y alertas (RAM, temperatura). Agrupada
// por día, con la hora a la izquierda para escanearla rápido.
// ══════════════════════════════════════════════════════════════

private enum class Filter(val label: String, val kinds: Set<String>?) {
    All("Todo", null),
    Sessions("Conexiones", setOf("session", "pairing", "agent")),
    Commands("Comandos", setOf("command")),
    Alerts("Alertas", setOf("alert")),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(session: PcSession) {
    val client = session.client
    val state by session.state.collectAsState()
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = haptics()
    var events by remember { mutableStateOf<List<ActivityEvent>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }

    suspend fun load() {
        client.call("activity", "recent", ActivityList.serializer(), buildJsonObject { put("limit", 300) })
            .onSuccess { events = it.events; error = null }
            .onFailure { if (events == null) error = it }
    }

    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED) return@LaunchedEffect
        while (isActive) { load(); delay(15_000) }
    }

    PcScaffold(title = "Actividad", subtitle = creds.agentName) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!plugins.let { session.isAvailable("activity") }) {
                if (plugins == null) EmptyState(Icons.Outlined.Timeline, "Actividad no disponible",
                    "Actualiza el agente del PC (versión 0.4 o posterior) para ver su actividad.")
                else PluginOffState("Actividad", onRefresh = session::refreshPlugins)
                return@Column
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { haptics.tick(); filter = f }, label = { Text(f.label) })
                }
            }
            val list = events?.filter { e -> filter.kinds?.contains(e.kind) ?: true }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    list == null && error == null -> SkeletonList()
                    list == null -> ErrorState("No se pudo leer la actividad", error?.message ?: "",
                                               onRetry = { scope.launch { load() } })
                    list.isEmpty() -> EmptyState(Icons.Outlined.Timeline, "Sin actividad",
                        if (filter == Filter.All) "Aún no ha pasado nada en este PC." else "No hay eventos de este tipo.")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        val byDay = list.groupBy { dayLabel(it.ts) }
                        byDay.forEach { (day, dayEvents) ->
                            item(key = "d-$day") {
                                Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                     modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
                            }
                            items(dayEvents, key = { "${it.ts}-${it.kind}-${it.title}-${it.device}" }) { e ->
                                ActivityItem(e, isLast = e === dayEvents.last())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One timeline row: time · node on a rail · title, detail and device. */
@Composable
fun ActivityItem(e: ActivityEvent, isLast: Boolean) {
    val (icon, color) = look(e)
    val rail = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Text(clockTime(e.ts), style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(top = 14.dp))
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!isLast) Box(Modifier.padding(top = 36.dp).width(2.dp).fillMaxHeight().background(rail))
            Box(
                Modifier.padding(top = 8.dp).size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(e.device, e.detail).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun look(e: ActivityEvent): Pair<ImageVector, Color> {
    val ext = PcRemoteTheme.extended
    val scheme = MaterialTheme.colorScheme
    if (e.level == "error") return Icons.Outlined.ErrorOutline to scheme.error
    return when (e.kind) {
        "alert" -> Icons.Outlined.WarningAmber to ext.warning
        "session" -> (if (e.title.contains("desconect", true)) Icons.Outlined.LinkOff else Icons.Outlined.Link) to ext.success
        "pairing" -> Icons.Outlined.PhonelinkSetup to scheme.tertiary
        "agent" -> Icons.Outlined.PowerSettingsNew to scheme.secondary
        else -> Icons.Outlined.Bolt to scheme.primary
    }
}
