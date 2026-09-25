package com.sack.pcremote

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import com.sack.pcremote.data.ThemeMode
import com.sack.pcremote.ui.PcRemoteApp
import com.sack.pcremote.ui.components.LocalSettings
import com.sack.pcremote.ui.theme.PcRemoteTheme

// FragmentActivity (still a ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PcRemoteApplication

        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Status / navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalSettings provides settings) {
                PcRemoteTheme(mode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                    PcRemoteApp(app)
                }
            }
        }
    }
}
