package com.sack.pcremote.ui

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.Composable
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.Discovery
import com.sack.pcremote.ui.screens.DashboardScreen
import com.sack.pcremote.ui.screens.DiscoveryScreen
import com.sack.pcremote.ui.screens.PairScreen

// ══════════════════════════════════════════════════════════════
// NavHost raíz. Rutas:
//   discovery                → lista de emparejados + mDNS scan
//   pair/{host}/{port}/{name}?code=&fp= → emparejar con código, o por QR si vienen code y fp
//   dashboard/{deviceId}      → un PC: inicio + touchpad, teclado, multimedia, apps, portapapeles
// ══════════════════════════════════════════════════════════════

// Route segments must not contain "/" or "?": a PC name like "Sala/TV" broke navigation.
private fun enc(s: String) = android.net.Uri.encode(s)

@Composable
fun PcRemoteApp(nav: NavHostController, store: CredentialsStore, discovery: Discovery) {
    NavHost(navController = nav, startDestination = "discovery") {

        composable("discovery") {
            DiscoveryScreen(
                store     = store,
                discovery = discovery,
                onPair    = { agent ->
                    nav.navigate("pair/${enc(agent.host)}/${agent.port}/${enc(agent.name)}")
                },
                onPairQr  = { qr ->
                    nav.navigate("pair/${enc(qr.host)}/${qr.port}/${enc(qr.name)}?code=${qr.code}&fp=${qr.fp}")
                },
                onOpen    = { deviceId ->
                    nav.navigate("dashboard/$deviceId")
                },
            )
        }

        composable(
            "pair/{host}/{port}/{name}?code={code}&fp={fp}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("fp") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStack ->
            val args = backStack.arguments
            // Navigation already decodes path arguments; decoding again would mangle a "%" in a name.
            val host = args?.getString("host") ?: ""
            val port = args?.getString("port")?.toIntOrNull() ?: 47820
            val name = args?.getString("name") ?: ""
            PairScreen(
                host = host, port = port, agentName = name, store = store,
                // Straight into the new PC; Back from there returns to the list, not to pairing.
                onPaired = { deviceId ->
                    nav.navigate("dashboard/$deviceId") { popUpTo("discovery") { inclusive = false } }
                },
                onBack = { nav.popBackStack("discovery", inclusive = false) },
                qrCode = args?.getString("code"),
                qrFingerprint = args?.getString("fp"),
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
