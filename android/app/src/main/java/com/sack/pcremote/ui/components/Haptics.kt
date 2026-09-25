package com.sack.pcremote.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Haptic feedback with meaning, not just "a vibration": confirm (an action went
 * through, connected), reject (error), toggle, tick (keys, segments) and long
 * press. Everything goes through here so the "Vibración" setting turns all of it off.
 */
class Haptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun reject() = perform(HapticFeedbackType.Reject)
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    fun key() = perform(HapticFeedbackType.KeyboardTap)
    fun longPress() = perform(HapticFeedbackType.LongPress)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

val LocalHaptics = compositionLocalOf<Haptics?> { null }

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}

/** The app's haptics (falls back to enabled haptics outside the app shell, e.g. previews). */
@Composable
fun haptics(): Haptics = LocalHaptics.current ?: rememberHaptics(true)
