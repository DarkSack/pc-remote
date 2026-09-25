package com.sack.pcremote.ui.pc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.ActivityEvent
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.extendedColors
import java.util.Calendar

// ══════════════════════════════════════════════════════════════
// Actividad: línea de tiempo de lo que pasa en el PC (conexiones,
// energía, apps, alertas, plugins…), agrupada por día.
// ══════════════════════════════════════════════════════════════

private enum class ActivityFilter(val label: String, val types: Set<String>?) {
    All("Todo", null),
    Alerts("Alertas", setOf("alert")),
    Connection("Conexiones", setOf("connection")),
    Power("Energía", setOf("power", "agent")),
    Actions("Acciones", setOf("app", "process", "plugin", "terminal", "files", "clipboard")),
}

private fun iconFor(type: String): ImageVector = when (type) {
    "connection" -> Icons.Outlined.Link
    "power" -> Icons.Outlined.PowerSettingsNew
    "agent" -> Icons.Outlined.Computer
    "alert" -> Icons.Outlined.WarningAmber
    "app" -> Icons.Outlined.Apps
    "process" -> Icons.Outlined.Memory
    "plugin" -> Icons.Outlined.Extension
    "terminal" -> Icons.Outlined.Terminal
    "files" -> Icons.Outlined.Folder
    "clipboard" -> Icons.Outlined.ContentPaste
    else -> Icons.Outlined.Info
}

@Composable
private fun severityColors(severity: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val ext = MaterialTheme.extendedColors
    return when (severity) {
        "success" -> ext.successContainer to ext.onSuccessContainer
        "warning" -> ext.warningContainer to ext.onWarningContainer
        "error" -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHigh to cs.onSurfaceVariant
    }
}

private fun dayLabel(ts: Long, now: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(c, today)) return "Hoy"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(c, today)) return "Ayer"
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.forLanguageTag("es")).format(java.util.Date(ts))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: PcSession) {
    val events by vm.activity.collectAsStateWithLifecycle()
    val loaded by vm.activityLoaded.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val now = rememberNow(30_000)

    val shown = remember(events, filter) { val types = filter.types; events.filter { types == null || it.type in types } }
    val grouped = remember(shown, now) { shown.groupBy { dayLabel(it.ts, now) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Actividad") },
                actions = { ConnectionBadge(state, Modifier.padding(end = 16.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityFilter.entries.forEach { f ->
                    val count = if (f.types == null) events.size else events.count { it.type in f.types }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(if (count > 0) "${f.label} · $count" else f.label) })
                }
            }
            when {
                !loaded && events.isEmpty() -> SkeletonList(8)
                shown.isEmpty() -> EmptyState(
                    Icons.Outlined.Timeline,
                    if (filter == ActivityFilter.All) "Sin actividad todavía" else "Nada en «${filter.label}»",
                    "Aquí aparecen las conexiones, el encendido, las apps que abras, las alertas del hardware y lo que hagan los plugins.",
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    grouped.forEach { (day, list) ->
                        item(key = "d-$day") { SectionHeader(day) }
                        items(list, key = { it.id }) { ev ->
                            ActivityItem(ev, first = ev === list.first(), last = ev === list.last(), now = now)
                        }
                    }
                }
            }
        }
    }
}

/** One row of the timeline: rail + icon, title, detail and time. */
@Composable
fun ActivityItem(ev: ActivityEvent, first: Boolean, last: Boolean, now: Long) {
    val (container, content) = severityColors(ev.severity)
    val rail = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                if (!first) drawLine(rail, Offset(x, 0f), Offset(x, 20.dp.toPx()), strokeWidth = 2.dp.toPx())
                if (!last) drawLine(rail, Offset(x, 20.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
            IconTile(iconFor(ev.type), Modifier.padding(top = 4.dp), size = 32.dp, container = container, content = content)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(ev.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatClock(ev.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ev.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            if (now - ev.ts < 3_600_000) {
                Text(formatAgo(ev.ts, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
