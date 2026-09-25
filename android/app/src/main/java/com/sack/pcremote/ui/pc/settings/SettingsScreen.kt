package com.sack.pcremote.ui.pc.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sack.pcremote.data.SettingsStore
import com.sack.pcremote.data.ThemeMode
import com.sack.pcremote.net.PluginInfo
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import com.sack.pcremote.ui.lock.canUseAppLock
import com.sack.pcremote.ui.pc.PcScaffold
import com.sack.pcremote.ui.theme.MonoStyle
import com.sack.pcremote.ui.theme.PcRemoteTheme

@Composable
fun SettingsScreen(session: PcSession, store: SettingsStore, onSwitchPc: () -> Unit, onUnpaired: () -> Unit) {
    val ctx = LocalContext.current
    val settings by store.settings.collectAsState()
    val creds by session.creds.collectAsState()
    val info by session.info.collectAsState()
    val plugins by session.plugins.collectAsState()
    val haptics = haptics()

    var showTheme by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val activity = ctx as? FragmentActivity
    val lockSupported = remember(activity) { activity?.let(::canUseAppLock) ?: false }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    fun set(transform: (com.sack.pcremote.data.AppSettings) -> com.sack.pcremote.data.AppSettings) = store.update(transform)

    PcScaffold(
        title = "Ajustes",
        subtitle = creds.agentName,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Más opciones") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reconectar ahora") }, leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                                     onClick = { menu = false; haptics.tick(); session.retry() })
                    DropdownMenuItem(text = { Text("Cambiar de equipo") }, leadingIcon = { Icon(Icons.Outlined.Devices, null) },
                                     onClick = { menu = false; onSwitchPc() })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {

            Group("Conexión")
            Item(Icons.Outlined.DesktopWindows, "Equipo", info?.hostname ?: creds.agentName)
            Item(Icons.Outlined.Lan, "Dirección del servidor", "${creds.agentHost}:${creds.agentPort}", mono = true,
                 onClick = { showAddress = true })
            Item(Icons.Outlined.Key, "Autenticación",
                 "Clave Ed25519 de este móvil · certificado del PC fijado: ${creds.certFingerprintHex.take(16)}…")
            Item(Icons.Outlined.Extension, "Plugins del PC",
                 plugins?.let { list -> "${list.count { it.enabled }} de ${list.size} activos" } ?: "Agente sin plugins (versión anterior a 0.4)",
                 onClick = if (plugins != null) ({ showPlugins = !showPlugins }) else null)
            AnimatedVisibility(showPlugins && plugins != null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    plugins?.forEach { PluginRow(it) }
                    Text("Los plugins se activan y desactivan en el panel del agente, en el PC.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedButton(onClick = session::refreshPlugins) { Text("Actualizar") }
                }
            }
            info?.agentVersion?.let { Item(Icons.Outlined.Info, "Versión del agente", "v$it") }

            Group("Apariencia")
            Item(Icons.Outlined.Palette, "Tema", when (settings.theme) {
                ThemeMode.SYSTEM -> "Según el sistema"; ThemeMode.DARK -> "Oscuro"; ThemeMode.LIGHT -> "Claro"
            }, onClick = { showTheme = true })
            SwitchItem(Icons.Outlined.AutoAwesome, "Colores dinámicos",
                       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Usar los colores de tu fondo de pantalla" else "Requiere Android 12",
                       checked = settings.dynamicColor, enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                haptics.toggle(it); set { s -> s.copy(dynamicColor = it) }
            }

            Group("Comportamiento")
            SwitchItem(Icons.Outlined.GppMaybe, "Confirmar acciones", "Pedir confirmación antes de suspender, finalizar procesos…",
                       checked = settings.confirmDestructive) { haptics.toggle(it); set { s -> s.copy(confirmDestructive = it) } }
            SwitchItem(Icons.Outlined.Vibration, "Vibración", "Respuesta háptica en botones, teclas y avisos",
                       checked = settings.haptics) { set { s -> s.copy(haptics = it) } }
            SwitchItem(Icons.Outlined.LightMode, "Pantalla siempre encendida en Control", "El móvil no se bloquea mientras usas el ratón o el teclado",
                       checked = settings.keepScreenOnInControl) { haptics.toggle(it); set { s -> s.copy(keepScreenOnInControl = it) } }

            Group("Seguridad")
            SwitchItem(Icons.Outlined.Fingerprint, "Desbloqueo biométrico",
                       if (lockSupported) "Pedir huella, cara o PIN al abrir la app" else "Configura un bloqueo de pantalla en el móvil para usarlo",
                       checked = settings.biometricLock && lockSupported, enabled = lockSupported) {
                haptics.toggle(it); set { s -> s.copy(biometricLock = it) }
            }
            Item(Icons.Outlined.LinkOff, "Desemparejar este PC", "Borra las credenciales de este móvil", danger = true,
                 onClick = { confirmUnpair = true })

            Group("Acerca de")
            Item(Icons.Outlined.Info, "PC Remote", "Versión $version · hecho por Sack")
            Item(Icons.Outlined.Description, "Licencias de código abierto", "Bibliotecas que usa la app", onClick = { showLicenses = true })
        }
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Tema") },
            text = {
                Column(Modifier.selectableGroup()) {
                    listOf(ThemeMode.SYSTEM to "Según el sistema", ThemeMode.DARK to "Oscuro", ThemeMode.LIGHT to "Claro").forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = settings.theme == mode, role = Role.RadioButton, onClick = {
                                    haptics.tick(); set { it.copy(theme = mode) }; showTheme = false
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.theme == mode, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Cerrar") } },
        )
    }

    if (showAddress) {
        var host by remember { mutableStateOf(creds.agentHost) }
        var port by remember { mutableStateOf(creds.agentPort.toString()) }
        val portOk = port.toIntOrNull() in 1..65535
        AlertDialog(
            onDismissRequest = { showAddress = false },
            icon = { Icon(Icons.Outlined.Lan, null) },
            title = { Text("Dirección del PC") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Normalmente se actualiza sola si el PC cambia de IP. Cámbiala a mano solo si no lo encuentra.",
                         style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP o nombre") }, singleLine = true,
                                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Puerto") }, singleLine = true,
                                      isError = !portOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank() && portOk, onClick = {
                    showAddress = false; haptics.confirm(); session.changeAddress(host, port.toInt())
                }) { Text("Guardar y reconectar") }
            },
            dismissButton = { TextButton(onClick = { showAddress = false }) { Text("Cancelar") } },
        )
    }

    if (confirmUnpair) {
        ConfirmDialog(
            title = "¿Desemparejar ${creds.agentName}?",
            message = "Se borran las credenciales de este móvil. Para volver a usar el PC habrá que emparejarlo de nuevo.",
            confirmLabel = "Desemparejar",
            icon = Icons.Outlined.LinkOff,
            onConfirm = { session.unpair(); onUnpaired() },
            onDismiss = { confirmUnpair = false },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licencias de código abierto") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LICENSES.forEach { (name, license) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Cerrar") } },
        )
    }
}

private val LICENSES = listOf(
    "AndroidX, Jetpack Compose, Material 3" to "Apache License 2.0 · Google",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0 · JetBrains",
    "OkHttp, Okio" to "Apache License 2.0 · Square",
    "Bouncy Castle" to "MIT (Bouncy Castle License)",
    "ML Kit Code Scanner (Google Play services)" to "Términos de Google APIs",
    "Material Symbols" to "Apache License 2.0 · Google",
)

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
         modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun Item(
    icon: ImageVector, title: String, subtitle: String,
    mono: Boolean = false, danger: Boolean = false, onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { Text(subtitle, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
                                   maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector, title: String, subtitle: String, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = checked, enabled = enabled, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

@Composable
private fun PluginRow(p: PluginInfo) {
    val ext = PcRemoteTheme.extended
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (p.enabled) ext.success else MaterialTheme.colorScheme.outline, live = false, size = 5.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name + if (!p.builtIn) " (externo)" else "", style = MaterialTheme.typography.bodyMedium)
            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (p.enabled) "Activo" else "Desactivado", style = MaterialTheme.typography.labelMedium,
             color = if (p.enabled) ext.success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
