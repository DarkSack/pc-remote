package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sack.pcremote.PcRemoteApplication
import com.sack.pcremote.session.PcSession

// ══════════════════════════════════════════════════════════════
// El Command Center de un PC. Cinco destinos principales; todo lo
// demás (monitor, procesos, red, terminal, archivos, portapapeles,
// plugins) vive dentro de ellos como pantallas secundarias.
//
// NavigationSuiteScaffold pone barra inferior en teléfonos y
// NavigationRail en tablets / plegables abiertos.
// ══════════════════════════════════════════════════════════════

enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("home", "Inicio", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard),
    Control("control", "Control", Icons.Outlined.Mouse, Icons.Filled.Mouse),
    Apps("apps", "Apps", Icons.Outlined.Apps, Icons.Filled.Apps),
    Activity("activity", "Actividad", Icons.Outlined.Timeline, Icons.Filled.Timeline),
    Settings("settings", "Ajustes", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Secondary screens, reached from Home ("Herramientas") or elsewhere. */
object Routes {
    const val Monitor = "monitor"
    const val Processes = "processes"
    const val Network = "network"
    const val Terminal = "terminal"
    const val Files = "files"
    const val Clipboard = "clipboard"
    const val Plugins = "plugins"
}

@Composable
fun PcScaffold(deviceId: String, onExit: () -> Unit, onUnpaired: () -> Unit) {
    val app = LocalContext.current.applicationContext as PcRemoteApplication
    val vm: PcSession = viewModel(key = "pc-$deviceId") { PcSession(app, deviceId) }
    if (vm.client == null) {
        // Credentials gone (unpaired from another screen, storage wiped).
        LaunchedEffect(Unit) { onUnpaired() }
        return
    }

    // The connection follows the app: checked (or re-opened) when it comes back,
    // dropped 30 s after it goes to the background.
    LifecycleStartEffect(vm) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                val selected = current == tab.route
                item(
                    selected = selected,
                    onClick = { nav.goToTab(tab) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) { HomeScreen(vm, open = nav::navigate, onSwitchPc = onExit) }
                composable(Tab.Control.route) { ControlScreen(vm) }
                composable(Tab.Apps.route) { AppsScreen(vm) }
                composable(Tab.Activity.route) { ActivityScreen(vm) }
                composable(Tab.Settings.route) {
                    SettingsScreen(vm, onSwitchPc = onExit, onUnpaired = { vm.unpair(); onUnpaired() }, open = nav::navigate)
                }
                val back: () -> Unit = { nav.popBackStack() }
                composable(Routes.Monitor) { MonitorScreen(vm, back) }
                composable(Routes.Processes) { ProcessesScreen(vm, back) }
                composable(Routes.Network) { NetworkScreen(vm, back) }
                composable(Routes.Terminal) { TerminalScreen(vm, back) }
                composable(Routes.Files) { FilesScreen(vm, back) }
                composable(Routes.Clipboard) { ClipboardScreen(vm, back) }
                composable(Routes.Plugins) { PluginsScreen(vm, back) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

private fun NavHostController.goToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

