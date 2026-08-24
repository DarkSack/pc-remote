package com.sack.pcremote.ui

import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.Composable
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.Discovery
import com.sack.pcremote.ui.screens.DashboardScreen
import com.sack.pcremote.ui.screens.DiscoveryScreen
import com.sack.pcremote.ui.screens.PairScreen

// ══════════════════════════════════════════════════════════════
// NavHost raíz. Rutas:
//   discovery                → lista de emparejados + mDNS scan
//   pair/{host}/{port}/{name} → pair flow con código 6 dígitos
//   dashboard/{deviceId}      → stats + power + (Fase 5: subscreens)
// ══════════════════════════════════════════════════════════════

@Composable
fun PcRemoteApp(nav: NavHostController, store: CredentialsStore, discovery: Discovery) {
    NavHost(navController = nav, startDestination = "discovery") {

        composable("discovery") {
            DiscoveryScreen(
                store     = store,
                discovery = discovery,
                onPair    = { agent ->
                    nav.navigate("pair/${agent.host}/${agent.port}/${java.net.URLEncoder.encode(agent.name, "UTF-8")}")
                },
                onOpen    = { deviceId ->
                    nav.navigate("dashboard/$deviceId")
                },
            )
        }

        composable("pair/{host}/{port}/{name}") { backStack ->
            val host = backStack.arguments?.getString("host") ?: ""
            val port = backStack.arguments?.getString("port")?.toIntOrNull() ?: 47820
            val name = java.net.URLDecoder.decode(backStack.arguments?.getString("name") ?: "", "UTF-8")
            PairScreen(
                host = host, port = port, agentName = name, store = store,
                onDone = { nav.popBackStack("discovery", inclusive = false) },
            )
        }

        composable("dashboard/{deviceId}") { backStack ->
            val id = backStack.arguments?.getString("deviceId") ?: return@composable
            DashboardScreen(
                deviceId = id,
                store    = store,
                onBack   = { nav.popBackStack() },
            )
        }
    }
}
