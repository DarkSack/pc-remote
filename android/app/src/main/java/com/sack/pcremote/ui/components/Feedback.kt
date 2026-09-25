package com.sack.pcremote.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sack.pcremote.data.Settings

/** App settings, provided once at the root. */
val LocalSettings = compositionLocalOf { Settings() }

/** Haptics that respect the "Vibración" setting. */
class Haptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun tick() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    fun toggle(on: Boolean) {
        if (enabled) feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    }
    fun confirm() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.Confirm) }
    fun reject() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.Reject) }
    fun longPress() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalSettings.current.haptics
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}

/** Plain-text clipboard of the phone (the system one, synchronous and not deprecated). */
class TextClipboard(private val context: android.content.Context) {
    private val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    fun copy(text: String, label: String = "PC Remote") = cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    fun paste(): String? = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

@Composable
fun rememberTextClipboard(): TextClipboard {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) { TextClipboard(ctx.applicationContext) }
}
