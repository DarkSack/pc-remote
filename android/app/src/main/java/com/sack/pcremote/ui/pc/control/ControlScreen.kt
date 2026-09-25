package com.sack.pcremote.ui.pc.control

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.sack.pcremote.data.SettingsStore
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.PluginOffState
import com.sack.pcremote.ui.components.haptics
import com.sack.pcremote.ui.pc.PcScaffold

// ══════════════════════════════════════════════════════════════
// Control remoto: touchpad, teclado, multimedia y atajos, en pestañas.
// La pantalla se mantiene encendida mientras estás aquí (ajustable):
// usar el touchpad y que el móvil se bloquee a los 30 s es frustrante.
// ══════════════════════════════════════════════════════════════

private enum class ControlTab(val label: String, val icon: ImageVector, val domain: String) {
    Touchpad("Ratón", Icons.Outlined.Mouse, "input"),
    Keyboard("Teclado", Icons.Outlined.Keyboard, "input"),
    Media("Media", Icons.Outlined.MusicNote, "media"),
    Shortcuts("Atajos", Icons.Outlined.Bolt, "input"),
}

@Composable
fun ControlScreen(session: PcSession, settingsStore: SettingsStore) {
    val settings by settingsStore.settings.collectAsState()
    val state by session.state.collectAsState()
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val haptics = haptics()
    var tab by rememberSaveable { mutableStateOf(ControlTab.Touchpad) }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOnInControl) {
        view.keepScreenOn = settings.keepScreenOnInControl
        onDispose { view.keepScreenOn = false }
    }

    PcScaffold(title = "Control", subtitle = creds.agentName) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                ControlTab.entries.forEach { t ->
                    Tab(
                        selected = t == tab,
                        onClick = { haptics.tick(); tab = t },
                        text = { Text(t.label, maxLines = 1) },
                        icon = { Icon(t.icon, contentDescription = null) },
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                val available = plugins.let { session.isAvailable(tab.domain) }
                when {
                    !available -> PluginOffState(if (tab.domain == "media") "Multimedia" else "Ratón y teclado",
                                                 onRefresh = session::refreshPlugins)
                    else -> when (tab) {
                        ControlTab.Touchpad -> TouchpadPanel(session.client, state == ConnectionState.CONNECTED,
                            sensitivity = settings.touchpadSensitivity,
                            onSensitivity = { v -> settingsStore.update { it.copy(touchpadSensitivity = v) } })
                        ControlTab.Keyboard -> KeyboardPanel(session.client)
                        ControlTab.Media -> MediaPanel(session.client, state)
                        ControlTab.Shortcuts -> ShortcutsPanel(session.client)
                    }
                }
            }
        }
    }
}
