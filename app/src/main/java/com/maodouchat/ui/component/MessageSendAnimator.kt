package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.maodouchat.ui.theme.LocalMotionSettings

/**
 * Wraps a composable to animate a Telegram-style message send fly-out effect.
 *
 * On send, the message springs into place with a slight overshoot: scale
 * (0.92 -> 1.0), alpha (0 -> 1) and a tiny upward slide. A spring
 * (dampingRatio ~0.72, stiffness ~400) gives the organic "pop in" feel;
 * collapses to an instant snap when animations are disabled.
 *
 * [sent] controls when the animation triggers; [onFinished] fires after completion.
 */
@Composable
fun MessageSendAnimator(
    sent: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    val progress = remember { Animatable(0f) }

    LaunchedEffect(sent, motion.animationsEnabled, motion.durationScale) {
        if (!sent) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (!motion.animationsEnabled) {
            progress.snapTo(1f)
            onFinished()
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = motion.springSpec(dampingRatio = 0.72f, stiffness = 400f)
        )
        onFinished()
    }

    val alpha = progress.value.coerceIn(0f, 1f)
    val scale = 0.92f + 0.08f * progress.value
    val translationY = 18f * (1f - progress.value)
    val translationX = 6f * (1f - progress.value)

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            this.scaleX = scale
            this.scaleY = scale
            this.translationY = translationY
            this.translationX = translationX
        }
    ) {
        content()
    }
}
