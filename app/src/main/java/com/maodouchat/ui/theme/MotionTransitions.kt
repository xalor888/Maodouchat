package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun rememberMotionPulse(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    label: String,
    active: Boolean = true,
    staticValue: Float = targetValue
): State<Float> {
    val motion = LocalMotionSettings.current
    if (!active || !motion.animationsEnabled) return rememberUpdatedState(staticValue)

    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = motion.duration(durationMillis)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}Value"
    )
}
