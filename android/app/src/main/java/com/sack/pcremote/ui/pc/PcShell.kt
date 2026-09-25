package com.sack.pcremote.ui.pc

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sack.pcremote.AppGraph
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.session.PcViewModel
import com.sack.pcremote.ui.components.ConnectionStatus
import com.sack.pcremote.ui.components.haptics
import com.sack.pcremote.ui.pc.activity.ActivityScreen
import com.sack.pcremote.ui.pc.apps.AppsScreen
import com.sack.pcremote.ui.pc.control.ControlScreen
import com.sack.pcremote.ui.pc.home.HomeScreen
import com.sack.pcremote.ui.pc.settings.SettingsScreen
import com.sack.pcremote.ui.pc.tools.ClipboardScreen
import com.sack.pcremote.ui.pc.tools.FilesScreen
import com.sack.pcremote.ui.pc.tools.NetworkScreen
import com.sack.pcremote.ui.pc.tools.TerminalScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// El Command Center de un PC.
//
// Cinco destinos principales (Inicio, Control, Apps, Actividad, Ajustes)
// con NavigationSuiteScaffold: barra inferior en móvil, NavigationRail en
// tablets y plegables abiertos. Las herramientas (Terminal, Archivos, Red,
// Portapapeles) cuelgan de Inicio.
//
// Una sola sesión (PcSession, en un ViewModel) para todas las pantallas:
// cambiar de pestaña no reconecta.
// ══════════════════════════════════════════════════════════════

@Serializable object HomeDest
@Serializable object ControlDest
@Serializable object AppsDest
@Serializable object ActivityDest
@Serializable object SettingsDest
@Serializable object TerminalDest
@Serializable object FilesDest
@Serializable object NetworkDest
@Serializable object ClipboardDest

private enum class PcTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val route: Any) {
    Home("Inicio", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard, HomeDest),
    Control("Control", Icons.Outlined.TouchApp, Icons.Filled.TouchApp, ControlDest),
    Apps("Apps", Icons.Outlined.Apps, Icons.Filled.Apps, AppsDest),
    Activity("Actividad", Icons.Outlined.Timeline, Icons.Filled.Timeline, ActivityDest),
    Settings("Ajustes", Icons.Outlined.Settings, Icons.Filled.Settings, SettingsDest),
}

/** Tool screens, opened from Home. */
enum class PcTool { Terminal, Files, Network, Clipboard }

@Composable
fun PcShell(deviceId: String, graph: AppGraph, onExit: () -> Unit, onUnpaired: () -> Unit) {
    val vm: PcViewModel = viewModel(key = "pc-$deviceId", factory = PcViewModel.Factory(graph.credentials, graph.discovery, deviceId))
    val session = vm.session
    if (session == null) {
        // Unpaired meanwhile (another screen deleted it): nothing to show.
        LaunchedEffect(Unit) { onUnpaired() }
        return
    }

    // The connection follows the app (see PcSession.onForeground / onBackground).
    LifecycleStartEffect(session) {
        session.onForeground()
        onStopOrDispose { session.onBackground() }
    }

    val everConnected by session.everConnected.collectAsState()
    // After the first connection, keep "Conectado" on screen for a moment.
    var showShell by remember { mutableStateOf(everConnected) }
    LaunchedEffect(everConnected) {
        if (everConnected && !showShell) { delay(900); showShell = true }
    }

    AnimatedContent(showShell, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "shell") { ready ->
        if (!ready) ConnectionScreen(session, onExit)
        else ShellContent(session, graph, onExit, onUnpaired)
    }
}

@Composable
private fun ShellContent(session: PcSession, graph: AppGraph, onExit: () -> Unit, onUnpaired: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val haptics = haptics()
    val destination = entry?.destination
    val current = PcTab.entries.firstOrNull { tab -> destination?.hierarchy()?.any { it.hasRoute(tab.route::class) } == true }
        ?: PcTab.Home // tool screens belong to Home

    val adaptive = currentWindowAdaptiveInfoV2()
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
    val insets = if (layoutType == NavigationSuiteType.NavigationBar) WindowInsets(0, 0, 0, 0)
                 else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)

    fun openTab(tab: PcTab) {
        if (tab == current && destination?.hasRoute(tab.route::class) == true) return
        haptics.tick()
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openTool(tool: PcTool) {
        nav.navigate(when (tool) {
            PcTool.Terminal -> TerminalDest
            PcTool.Files -> FilesDest
            PcTool.Network -> NetworkDest
            PcTool.Clipboard -> ClipboardDest
        })
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            PcTab.entries.forEach { tab ->
                val selected = tab == current
                item(
                    selected = selected,
                    onClick = { openTab(tab) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalShellInsets provides insets) {
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = nav,
                    startDestination = HomeDest,
                    enterTransition = { fadeIn(androidx.compose.animation.core.tween(180)) },
                    exitTransition = { fadeOut(androidx.compose.animation.core.tween(120)) },
                ) {
                    composable<HomeDest> { HomeScreen(session, onOpenTool = ::openTool, onOpenTab = { openTab(PcTab.Apps) }, onSwitchPc = onExit) }
                    composable<ControlDest> { ControlScreen(session, graph.settings) }
                    composable<AppsDest> { AppsScreen(session) }
                    composable<ActivityDest> { ActivityScreen(session) }
                    composable<SettingsDest> { SettingsScreen(session, graph.settings, onSwitchPc = onExit, onUnpaired = onUnpaired) }
                    composable<TerminalDest> { TerminalScreen(session, onBack = { nav.popBackStack() }) }
                    composable<FilesDest> { FilesScreen(session, onBack = { nav.popBackStack() }) }
                    composable<NetworkDest> { NetworkScreen(session, onBack = { nav.popBackStack() }) }
                    composable<ClipboardDest> { ClipboardScreen(session, onBack = { nav.popBackStack() }) }
                }
                ConnectionBanner(session, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private fun androidx.navigation.NavDestination.hierarchy() = generateSequence(this) { it.parent }

/**
 * Shown over the content while a connection that worked is being recovered.
 * A banner, not a blocking screen: the last data stays visible under it.
 */
@Composable
private fun ConnectionBanner(session: PcSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    val problem by session.problem.collectAsState()
    val haptics = haptics()
    val visible = state != ConnectionState.CONNECTED

    // Feedback when the connection comes back or is lost.
    var wasVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (wasVisible && !visible) haptics.confirm()
        wasVisible = visible
    }

    AnimatedVisibility(
        visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.padding(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ConnectionStatus(state, textColor = MaterialTheme.colorScheme.inverseOnSurface)
                    val p = problem
                    if (p != null) Text(p.title, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { haptics.tick(); session.retry() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                ) { Text("Reintentar") }
            }
        }
    }
}
