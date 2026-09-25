package com.sack.pcremote.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sack.pcremote.AppGraph
import com.sack.pcremote.ui.devices.DevicesScreen
import com.sack.pcremote.ui.devices.PairScreen
import com.sack.pcremote.ui.pc.PcShell
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// Navegación raíz (type-safe):
//   Devices              → tus PCs + los que se ven en la red + escanear QR
//   Pair(host, port, …)  → emparejar con código, o por QR si trae code y fp
//   Pc(deviceId)         → el Command Center de un PC (su propia navegación)
//
// Al abrir la app se entra directo al último PC usado; Atrás vuelve a la
// lista de equipos.
// ══════════════════════════════════════════════════════════════

@Serializable object DevicesRoute
@Serializable data class PairRoute(val host: String, val port: Int, val name: String, val code: String? = null, val fp: String? = null)
@Serializable data class PcRoute(val deviceId: String)

@Composable
fun PcRemoteApp(graph: AppGraph) {
    val nav = rememberNavController()

    // Straight into the last PC, once per launch (not on every recomposition).
    val lastPc = remember {
        graph.settings.settings.value.lastDeviceId?.takeIf { graph.credentials.load(it) != null }
    }
    LaunchedEffect(Unit) {
        if (lastPc != null && nav.currentDestination?.hasRoute(PcRoute::class) != true) nav.navigate(PcRoute(lastPc))
    }

    NavHost(
        navController = nav,
        startDestination = DevicesRoute,
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable<DevicesRoute> {
            DevicesScreen(
                graph = graph,
                onPair = { agent -> nav.navigate(PairRoute(agent.host, agent.port, agent.name)) },
                onPairQr = { qr -> nav.navigate(PairRoute(qr.host, qr.port, qr.name, qr.code, qr.fp)) },
                onOpen = { id -> nav.navigate(PcRoute(id)) },
            )
        }

        composable<PairRoute> { entry ->
            val r = entry.toRoute<PairRoute>()
            PairScreen(
                host = r.host, port = r.port, agentName = r.name,
                store = graph.credentials,
                qrCode = r.code, qrFingerprint = r.fp,
                // Straight into the new PC; Back from there returns to the list, not to pairing.
                onPaired = { id -> nav.navigate(PcRoute(id)) { popUpTo(DevicesRoute) } },
                onBack = { nav.popBackStack(DevicesRoute, inclusive = false) },
            )
        }

        composable<PcRoute> { entry ->
            val r = entry.toRoute<PcRoute>()
            LaunchedEffect(r.deviceId) { graph.settings.update { it.copy(lastDeviceId = r.deviceId) } }
            PcShell(
                deviceId = r.deviceId,
                graph = graph,
                onExit = { nav.popBackStack(DevicesRoute, inclusive = false) },
                onUnpaired = {
                    graph.settings.update { it.copy(lastDeviceId = null) }
                    nav.popBackStack(DevicesRoute, inclusive = false)
                },
            )
        }
    }
}
