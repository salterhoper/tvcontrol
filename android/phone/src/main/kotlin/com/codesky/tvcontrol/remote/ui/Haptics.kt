package com.codesky.tvcontrol.remote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * A small haptic tap, gated by the user's preference. Uses the platform view's
 * feedback so it respects the system's haptic settings.
 */
@Composable
fun rememberHaptic(enabled: Boolean): () -> Unit {
    val view = LocalView.current
    return remember(enabled, view) {
        {
            if (enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }
}
