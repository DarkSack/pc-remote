package com.sack.pcremote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val confirmDestructive: Boolean = true,
    val haptics: Boolean = true,
    /** Ask for fingerprint / screen lock when opening the app. */
    val appLock: Boolean = false,
    /** Local notification when the PC goes offline or raises an alert (while the app is open). */
    val alerts: Boolean = true,
)

/**
 * App preferences. Plain SharedPreferences (nothing secret here) exposed as a
 * StateFlow, so every screen recomposes when one changes.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun read() = Settings(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.DARK),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        confirmDestructive = prefs.getBoolean(KEY_CONFIRM, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        appLock = prefs.getBoolean(KEY_LOCK, false),
        alerts = prefs.getBoolean(KEY_ALERTS, true),
    )

    fun update(change: (Settings) -> Settings) {
        val next = change(_state.value)
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putBoolean(KEY_CONFIRM, next.confirmDestructive)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .putBoolean(KEY_LOCK, next.appLock)
            .putBoolean(KEY_ALERTS, next.alerts)
            .apply()
        _state.value = next
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_CONFIRM = "confirm_destructive"
        const val KEY_HAPTICS = "haptics"
        const val KEY_LOCK = "app_lock"
        const val KEY_ALERTS = "alerts"
    }
}
