package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.net.PcNotification
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*

// ══════════════════════════════════════════════════════════════
// Notificaciones de Windows: las que muestra el PC, de la más nueva a
// la más vieja. Solo lectura. Llegan mientras la app está abierta y
// conectada; no hay servicio en segundo plano.
// ══════════════════════════════════════════════════════════════

@Composable
fun NotificationsScreen(vm: PcSession, back: () -> Unit) {
    val n by vm.notifications.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)

    LaunchedEffect(state) { if (state == ConnectionState.CONNECTED) vm.refreshNotifications() }

    SubScreen(
        "Notificaciones", back,
        subtitle = if (n.items.isNotEmpty()) "Las ${n.items.size} últimas de ${vm.displayName()}" else null,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            RequireConnection(vm) {
                when {
                    n.disabled -> EmptyState(
                        Icons.Outlined.NotificationsOff,
                        "Las notificaciones están desactivadas",
                        "Vienen apagadas porque pueden traer mensajes privados o códigos de verificación. " +
                            "Actívalas en el PC: abre PC Remote desde la bandeja → «Funciones y plugins» → Notificaciones.",
                        actionLabel = "Comprobar de nuevo", onAction = vm::refreshNotifications,
                    )
                    n.error != null -> EmptyState(
                        Icons.Outlined.ErrorOutline, "No se pueden leer", n.error!!,
                        actionLabel = "Reintentar", onAction = vm::refreshNotifications,
                    )
                    !n.loaded -> SkeletonList(6)
                    n.items.isEmpty() -> EmptyState(
                        Icons.Outlined.Notifications,
                        "Sin notificaciones",
                        "Aquí aparecen las notificaciones que muestra Windows. Las nuevas llegan mientras la app está abierta y conectada.",
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(n.items, key = { it.id }) { NotificationRow(it, now) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: PcNotification, now: Long) {
    ListItem(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        overlineContent = { Text(item.app) },
        headlineContent = { Text(item.title) },
        supportingContent = item.body.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 6) } },
        trailingContent = {
            if (item.ts > 0) Text(
                if (now - item.ts < 3_600_000) formatAgo(item.ts, now) else formatDate(item.ts),
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
