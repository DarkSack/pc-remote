package com.sack.pcremote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ══════════════════════════════════════════════════════════════
// Preferencias de la app (no de un PC): tema, vibración, confirmaciones,
// bloqueo biométrico y el último PC abierto. SharedPreferences + StateFlow:
// pocas claves, y la UI se recompone sola al cambiarlas.
// ══════════════════════════════════════════════════════════════

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val haptics: Boolean = true,
    val confirmDestructive: Boolean = true,
    val keepScreenOnInControl: Boolean = true,
    val biometricLock: Boolean = false,
    /** Opened straight away on launch, so the app starts on the PC's dashboard. */
    val lastDeviceId: String? = null,
    val touchpadSensitivity: Float = 1.6f,
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(K_DYNAMIC, false),
        haptics = prefs.getBoolean(K_HAPTICS, true),
        confirmDestructive = prefs.getBoolean(K_CONFIRM, true),
        keepScreenOnInControl = prefs.getBoolean(K_SCREEN_ON, true),
        biometricLock = prefs.getBoolean(K_BIOMETRIC, false),
        lastDeviceId = prefs.getString(K_LAST_DEVICE, null),
        touchpadSensitivity = prefs.getFloat(K_SENSITIVITY, 1.6f),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(K_THEME, next.theme.name)
            .putBoolean(K_DYNAMIC, next.dynamicColor)
            .putBoolean(K_HAPTICS, next.haptics)
            .putBoolean(K_CONFIRM, next.confirmDestructive)
            .putBoolean(K_SCREEN_ON, next.keepScreenOnInControl)
            .putBoolean(K_BIOMETRIC, next.biometricLock)
            .putString(K_LAST_DEVICE, next.lastDeviceId)
            .putFloat(K_SENSITIVITY, next.touchpadSensitivity)
            .apply()
        _settings.value = next
    }

    private companion object {
        const val K_THEME = "theme"
        const val K_DYNAMIC = "dynamic_color"
        const val K_HAPTICS = "haptics"
        const val K_CONFIRM = "confirm_destructive"
        const val K_SCREEN_ON = "keep_screen_on"
        const val K_BIOMETRIC = "biometric_lock"
        const val K_LAST_DEVICE = "last_device"
        const val K_SENSITIVITY = "touchpad_sensitivity"
    }
}
