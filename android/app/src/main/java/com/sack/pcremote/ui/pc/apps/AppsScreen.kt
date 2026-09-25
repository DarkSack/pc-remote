package com.sack.pcremote.ui.pc.apps

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.PluginOffState
import com.sack.pcremote.ui.components.haptics
import com.sack.pcremote.ui.pc.PcScaffold

private enum class AppsTab(val label: String, val icon: ImageVector, val domain: String, val plugin: String) {
    Apps("Apps", Icons.Outlined.Apps, "applications", "Apps"),
    Processes("Procesos", Icons.Outlined.Memory, "processes", "Procesos"),
    Windows("Ventanas", Icons.Outlined.WebAsset, "windows", "Ventanas"),
}

@Composable
fun AppsScreen(session: PcSession) {
    val creds by session.creds.collectAsState()
    val plugins by session.plugins.collectAsState()
    val haptics = haptics()
    var tab by rememberSaveable { mutableStateOf(AppsTab.Apps) }
    val snackbar = remember { SnackbarHostState() }

    PcScaffold(title = "Aplicaciones", subtitle = creds.agentName, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                AppsTab.entries.forEach { t ->
                    Tab(selected = t == tab, onClick = { haptics.tick(); tab = t },
                        text = { Text(t.label) }, icon = { Icon(t.icon, contentDescription = null) })
                }
            }
            val available = plugins.let { session.isAvailable(tab.domain) }
            if (!available) {
                PluginOffState(tab.plugin, onRefresh = session::refreshPlugins)
            } else when (tab) {
                AppsTab.Apps -> AppsGrid(session, snackbar)
                AppsTab.Processes -> ProcessesPanel(session, snackbar)
                AppsTab.Windows -> WindowsPanel(session, snackbar)
            }
        }
    }
}
