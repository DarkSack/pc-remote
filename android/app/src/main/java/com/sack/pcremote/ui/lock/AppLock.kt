package com.sack.pcremote.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sack.pcremote.ui.components.BrandMark
import com.sack.pcremote.ui.components.haptics

/** Whether this phone can lock the app at all (fingerprint, face or a screen lock). */
fun canUseAppLock(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Full-screen lock shown on launch (and after a minute away) when "Desbloqueo
 * biométrico" is on. Asks right away; the button asks again after a cancel.
 */
@Composable
fun AppLock(activity: FragmentActivity, onUnlocked: () -> Unit) {
    val haptics = haptics()
    var error by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        if (!canUseAppLock(activity)) {
            // The screen lock was removed after enabling this: do not lock the user out.
            onUnlocked()
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    haptics.confirm()
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    error = errString.toString()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear PC Remote")
                .setSubtitle("Tu PC se controla desde esta app")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    LaunchedEffect(Unit) { prompt() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(size = 64.dp)
            Spacer(Modifier.height(24.dp))
            Text("PC Remote está bloqueada", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(error ?: "Usa tu huella, tu cara o el bloqueo de pantalla del móvil.",
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { prompt() }) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Desbloquear")
            }
        }
    }
}
