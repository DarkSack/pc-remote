package com.sack.pcremote.ui.screens

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sack.pcremote.R

/**
 * "Desbloqueo biométrico": the app asks for fingerprint / face / screen lock
 * before showing anything. Whoever holds an unlocked phone should not get the
 * PC for free.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        error = null
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear PC Remote")
            .setSubtitle("Confirma que eres tú")
            // Device credential as fallback, so a phone without fingerprint still works.
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                // No lock screen configured at all: nothing to check against, let the user in.
                if (code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == BiometricPrompt.ERROR_HW_NOT_PRESENT) onUnlocked()
                else error = message.toString()
            }
        }).authenticate(info)
    }

    LaunchedEffect(Unit) { prompt() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_logo_mark), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text("PC Remote está bloqueado", style = MaterialTheme.typography.titleLarge)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::prompt) { Text("Desbloquear") }
        }
    }
}
