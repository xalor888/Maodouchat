package com.maodouchat.util

import android.content.Context
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Runtime-gated haptics: no-op when admin disables haptics_enabled. */
object HapticGate {
    fun perform(context: Context, haptic: HapticFeedback, type: HapticFeedbackType) {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.HAPTICS)) return
        haptic.performHapticFeedback(type)
    }
}
