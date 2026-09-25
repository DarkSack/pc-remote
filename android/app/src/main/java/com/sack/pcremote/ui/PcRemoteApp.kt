package com.sack.pcremote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sack.pcremote.PcRemoteApplication
import com.sack.pcremote.ui.components.LocalSettings
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.screens.DevicesScreen
import com.sack.pcremote.ui.screens.LockScreen
import com.sack.pcremote.ui.screens.PairScreen

// ══════════════════════════════════════════════════════════════
// NavHost raíz:
//   devices                       → PCs emparejados + descubiertos (pantalla de conexión)
//   pair/{host}/{port}/{name}?code=&fp= → emparejar con código, o por QR
//   pc/{deviceId}                 → el Command Center de un PC (con su propia
//                                   navegación: Inicio, Control, Apps, Actividad, Ajustes)
// Al abrir la app se entra directamente en el último PC usado.
// ══════════════════════════════════════════════════════════════

// Route segments must not contain "/" or "?": a PC name like "Sala/TV" broke navigation.
private fun enc(s: String) = android.net.Uri.encode(s)

private const val LAST_PC = "last_pc"

@Composable
fun PcRemoteApp(app: PcRemoteApplication) {
    val settings = LocalSettings.current
    // Locked on every cold start when the setting is on; unlocking survives rotation.
    var unlocked by rememberSaveable { mutableStateOf(false) }
    if (settings.appLock && !unlocked) {
        LockScreen(onUnlocked = { unlocked = true })
        return
    }

    val nav = rememberNavController()
    val prefs = remember { app.getSharedPreferences("nav", android.content.Context.MODE_PRIVATE) }
    var autoOpened by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(navController = nav, startDestination = "devices") {
            composable("devices") {
                LaunchedEffect(Unit) {
                    if (autoOpened) return@LaunchedEffect
                    autoOpened = true
                    val last = prefs.getString(LAST_PC, null)
                    if (last != null && app.store.load(last) != null) nav.navigate("pc/$last")
                }
                DevicesScreen(
                    app = app,
                    onPair = { agent -> nav.navigate("pair/${enc(agent.host)}/${agent.port}/${enc(agent.name)}") },
                    onPairQr = { qr -> nav.navigate("pair/${enc(qr.host)}/${qr.port}/${enc(qr.name)}?code=${qr.code}&fp=${qr.fp}") },
                    onOpen = { id ->
                        prefs.edit().putString(LAST_PC, id).apply()
                        nav.navigate("pc/$id")
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
                PairScreen(
                    host = args?.getString("host") ?: "",
                    port = args?.getString("port")?.toIntOrNull() ?: 47820,
                    agentName = args?.getString("name") ?: "",
                    store = app.store,
                    // Straight into the new PC; Back from there returns to the list, not to pairing.
                    onPaired = { deviceId ->
                        prefs.edit().putString(LAST_PC, deviceId).apply()
                        nav.navigate("pc/$deviceId") { popUpTo("devices") { inclusive = false } }
                    },
                    onBack = { nav.popBackStack("devices", inclusive = false) },
                    qrCode = args?.getString("code"),
                    qrFingerprint = args?.getString("fp"),
                )
            }

            composable("pc/{deviceId}") { backStack ->
                val id = backStack.arguments?.getString("deviceId") ?: return@composable
                PcScaffold(
                    deviceId = id,
                    onExit = { nav.popBackStack("devices", inclusive = false) },
                    onUnpaired = {
                        prefs.edit().remove(LAST_PC).apply()
                        nav.popBackStack("devices", inclusive = false)
                    },
                )
            }
        }
    }
}
