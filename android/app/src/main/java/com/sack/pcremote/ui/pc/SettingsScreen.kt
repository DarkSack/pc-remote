package com.sack.pcremote.ui.pc

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.BuildConfig
import com.sack.pcremote.PcRemoteApplication
import com.sack.pcremote.data.ThemeMode
import com.sack.pcremote.net.ConnectionState
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.theme.MonoStyle

// ══════════════════════════════════════════════════════════════
// Ajustes: conexión con este PC, apariencia, comportamiento,
// seguridad y "acerca de". Lo que se activa en el PC (terminal,
// archivos, plugins) solo se cambia desde su panel, a propósito.
// ══════════════════════════════════════════════════════════════

private val LICENSES = listOf(
    "AndroidX / Jetpack Compose / Material 3" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "Bouncy Castle" to "MIT",
    "Google Play services code scanner" to "Android SDK License",
    "Material Symbols" to "Apache 2.0",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: PcSession, onSwitchPc: () -> Unit, onUnpaired: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PcRemoteApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val creds by vm.creds.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val since by vm.connectedSince.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var confirmUnpair by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val now = rememberNow()

    val canLock = remember {
        BiometricManager.from(ctx).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Connection ─────────────────────────────
            item { SectionHeader("Conexión") }
            item {
                SettingsGroup {
                    ListItem(
                        headlineContent = { Text(info?.hostname ?: creds?.agentName ?: "PC") },
                        supportingContent = { Text(listOfNotNull(info?.os, info?.username?.let { "usuario $it" }).joinToString(" · ").ifEmpty { "—" }) },
                        leadingContent = { IconTile(Icons.Outlined.Computer) },
                        trailingContent = { ConnectionBadge(state) },
                    )
                    InfoRow("Dirección", creds?.let { "${it.agentHost}:${it.agentPort}" } ?: "—", mono = true)
                    InfoRow("Latencia", latency?.let { "$it ms" } ?: "—")
                    InfoRow("Conectado", since?.let { formatDuration((now - it) / 1000) } ?: "—")
                    creds?.macAddress?.let { InfoRow("MAC (Wake-on-LAN)", it, mono = true) }
                    creds?.certFingerprintHex?.let { fp ->
                        InfoRow("Huella del certificado", fp.chunked(2).take(8).joinToString(":") + "…", mono = true)
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { haptics.tick(); vm.retry() }, enabled = state != ConnectionState.CONNECTED) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reconectar")
                        }
                        OutlinedButton(onClick = onSwitchPc) {
                            Icon(Icons.Outlined.Devices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Cambiar de PC")
                        }
                    }
                }
            }

            // ── Appearance ─────────────────────────────
            item { SectionHeader("Apariencia") }
            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text("Tema", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        val modes = listOf(ThemeMode.DARK to "Oscuro", ThemeMode.LIGHT to "Claro", ThemeMode.SYSTEM to "Sistema")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, (mode, label) ->
                                SegmentedButton(
                                    selected = settings.themeMode == mode,
                                    onClick = { haptics.tick(); app.settings.update { it.copy(themeMode = mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                ) { Text(label) }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(Icons.Outlined.Palette, "Colores dinámicos", "Usa los colores de tu fondo de pantalla (Android 12+)", settings.dynamicColor) { v ->
                            haptics.toggle(v); app.settings.update { it.copy(dynamicColor = v) }
                        }
                    }
                }
            }

            // ── Behaviour ─────────────────────────────
            item { SectionHeader("Comportamiento") }
            item {
                SettingsGroup {
                    SwitchRow(Icons.Outlined.GppMaybe, "Confirmar acciones destructivas", "Apagar, reiniciar, cerrar apps o procesos piden confirmación", settings.confirmDestructive) { v ->
                        haptics.toggle(v); app.settings.update { it.copy(confirmDestructive = v) }
                    }
                    SwitchRow(Icons.Outlined.Vibration, "Vibración", "Respuesta háptica en botones y gestos", settings.haptics) { v ->
                        app.settings.update { it.copy(haptics = v) }
                    }
                    SwitchRow(Icons.Outlined.NotificationsActive, "Alertas del PC", "Aviso cuando la CPU, GPU, RAM o el disco pasan de lo normal", settings.alerts) { v ->
                        haptics.toggle(v); app.settings.update { it.copy(alerts = v) }
                    }
                }
            }

            // ── Security ──────────────────────────────
            item { SectionHeader("Seguridad") }
            item {
                SettingsGroup {
                    SwitchRow(
                        Icons.Outlined.Fingerprint, "Bloquear la app",
                        if (canLock) "Pide huella, cara o el PIN del móvil al abrirla" else "Configura un bloqueo de pantalla en el móvil para usarlo",
                        settings.appLock, enabled = canLock || settings.appLock,
                    ) { v -> haptics.toggle(v); app.settings.update { it.copy(appLock = v) } }
                    ListItem(
                        headlineContent = { Text("Funciones del PC") },
                        supportingContent = { Text("Terminal, archivos y plugins se activan solo desde el panel del agente en el PC. Así nadie puede activarlos desde un móvil.") },
                        leadingContent = { Icon(Icons.Outlined.AdminPanelSettings, null) },
                        trailingContent = { TextButton(onClick = { open(Routes.Plugins) }) { Text("Ver") } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    ListItem(
                        headlineContent = { Text("Olvidar este PC", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("Borra las claves de este móvil. Para volver a usarlo habrá que emparejar de nuevo.") },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.clickableRow { confirmUnpair = true },
                    )
                }
            }

            // ── About ─────────────────────────────────
            item { SectionHeader("Acerca de") }
            item {
                SettingsGroup {
                    InfoRow("PC Remote para Android", BuildConfig.VERSION_NAME)
                    InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    ListItem(
                        headlineContent = { Text("Licencias de código abierto") },
                        leadingContent = { Icon(Icons.Outlined.Description, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.clickableRow { showLicenses = true },
                    )
                }
            }
        }
    }

    if (confirmUnpair) {
        ConfirmDialog(
            title = "¿Olvidar ${creds?.agentName ?: "este PC"}?",
            text = "Este móvil dejará de poder controlarlo hasta que lo vuelvas a emparejar. En el PC puedes revocarlo también desde su panel.",
            confirmLabel = "Olvidar",
            icon = Icons.AutoMirrored.Outlined.Logout,
            onConfirm = { haptics.confirm(); onUnpaired() },
            onDismiss = { confirmUnpair = false },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licencias") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LICENSES.forEach { (name, license) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SelectionContainer { Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clickableRow(enabled) { onChange(!checked) },
    )
}

private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit) =
    this.clickable(enabled = enabled, onClick = onClick)
