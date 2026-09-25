package com.sack.pcremote

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.data.ThemeMode
import com.sack.pcremote.ui.PcRemoteApp
import com.sack.pcremote.ui.components.LocalHaptics
import com.sack.pcremote.ui.components.rememberHaptics
import com.sack.pcremote.ui.lock.AppLock
import com.sack.pcremote.ui.theme.PcRemoteTheme

// FragmentActivity (not ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {

    /** When the app went to the background, for re-locking after a while away. */
    private var stoppedAt: Long? = null
    private var locked by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph.get(this)
        // A rotation must not ask for the fingerprint again.
        locked = savedInstanceState?.getBoolean(KEY_LOCKED) ?: true

        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Status / navigation bar icons follow the app's theme, not only the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }

            PcRemoteTheme(mode = settings.theme, dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalHaptics provides rememberHaptics(settings.haptics)) {
                    if (settings.biometricLock && locked) {
                        AppLock(activity = this, onUnlocked = { locked = false })
                    } else {
                        PcRemoteApp(graph)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val away = stoppedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        stoppedAt = null
        if (away > RELOCK_AFTER_MS) locked = true
    }

    override fun onStop() {
        super.onStop()
        // A configuration change is not "leaving the app".
        if (!isChangingConfigurations) stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCKED, locked)
    }

    private companion object {
        const val KEY_LOCKED = "locked"
        const val RELOCK_AFTER_MS = 60_000L
        // Same scrims enableEdgeToEdge uses by default for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
