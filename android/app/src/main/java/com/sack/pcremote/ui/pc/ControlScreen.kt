package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.ConnectionBadge
import com.sack.pcremote.ui.components.rememberHaptics
import com.sack.pcremote.ui.remote.KeyboardPanel
import com.sack.pcremote.ui.remote.MediaPanel
import com.sack.pcremote.ui.remote.TouchpadPanel

// ══════════════════════════════════════════════════════════════
// Control remoto: ratón (superficie táctil), teclado y multimedia.
// ══════════════════════════════════════════════════════════════

private enum class ControlTab(val label: String, val icon: ImageVector) {
    Mouse("Ratón", Icons.Outlined.TouchApp),
    Keyboard("Teclado", Icons.Outlined.Keyboard),
    Media("Multimedia", Icons.Outlined.MusicNote),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(vm: PcSession) {
    val client = vm.client ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(ControlTab.Mouse) }
    val haptics = rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control") },
                actions = { ConnectionBadge(state, Modifier.padding(end = 16.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                ControlTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { haptics.tick(); tab = t },
                        text = { Text(t.label) },
                        icon = { Icon(t.icon, contentDescription = null) },
                    )
                }
            }
            RequireConnection(vm) {
                when (tab) {
                    // The touchpad fills the space: no scroll, it would steal the gestures.
                    ControlTab.Mouse -> Box(Modifier.fillMaxSize().padding(16.dp)) { TouchpadPanel(client) }
                    ControlTab.Keyboard -> Box(Modifier.fillMaxSize().padding(16.dp)) { KeyboardPanel(client) }
                    ControlTab.Media -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { MediaPanel(client, state) }
                }
            }
        }
    }
}
