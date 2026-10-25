package com.sack.pcremote.pro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** Pro para las pantallas: si está activo y cómo ofrecerlo cuando no. */
class ProAccess(val active: Boolean, val offer: () -> Unit) {
    /** Hace [action] si Pro está activo; si no, ofrece Pro. */
    fun require(action: () -> Unit) = if (active) action() else offer()
}

val LocalPro = compositionLocalOf { ProAccess(active = true, offer = {}) }

@Composable
fun ProDialog(price: String?, onBuy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WorkspacePremium, null) },
        title = { Text("Versión Pro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Un solo pago y es tuya para siempre:")
                Text("• Pantalla remota del PC, con sonido")
                Text("• Archivos, terminal y plugins")
                Text("• Notificaciones de Windows e historial del portapapeles")
                Text("• Varios PCs en el mismo móvil")
                Text("El ratón, el teclado, la multimedia, las apps y apagar o bloquear el PC siguen gratis.",
                    style = MaterialTheme.typography.bodySmall)
                if (price == null) Text("Conectando con Google Play…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onBuy, enabled = price != null) {
                Text(if (price != null) "Desbloquear por $price" else "Desbloquear")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ahora no") } },
    )
}
