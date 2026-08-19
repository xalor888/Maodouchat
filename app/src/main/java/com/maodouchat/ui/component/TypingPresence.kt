package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary

/**
 * Typing indicator with animated bouncing dots.
 *
 * Uses AnimatedVisibility for slide up/down entry/exit.
 * Three dots bounce with staggered infinite animations.
 *
 * @param visible Whether the typing indicator is shown
 * @param modifier Modifier for the outer container
 */
@Composable
fun TypingPresence(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotionSettings.current

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (!motion.animationsEnabled) {
            androidx.compose.animation.fadeIn(snap())
        } else {
            slideInVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
            ) { it / 2 } + fadeIn(
                spring(dampingRatio = 0.7f, stiffness = 400f)
            )
        },
        exit = if (!motion.animationsEnabled) {
            androidx.compose.animation.fadeOut(snap())
        } else {
            slideOutVertically(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)
            ) { it / 2 } + fadeOut(
                spring(dampingRatio = 0.8f, stiffness = 500f)
            )
        }
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .height(36.dp)
                .width(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (motion.animationsEnabled) {
                val transition = rememberInfiniteTransition(label = "typingPresence")
                val phase by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = motion.duration(1000),
                            easing = androidx.compose.animation.core.LinearEasing
                        ),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "typingPresencePhase"
                )
                repeat(3) { index ->
                    val sinVal = kotlin.math.sin(phase - index * 0.85f).coerceIn(-1f, 1f)
                    val norm = (sinVal + 1f) / 2f
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer {
                                translationY = -norm * 6.dp.toPx()
                                val s = 0.8f + 0.35f * norm
                                scaleX = s
                                scaleY = s
                                alpha = 0.45f + 0.55f * norm
                            }
                            .clip(RoundedCornerShape(3.5.dp))
                            .background(Primary)
                    )
                }
            } else {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer { alpha = 0.7f }
                            .clip(RoundedCornerShape(3.5.dp))
                            .background(Primary)
                    )
                }
            }
        }
    }
}

private fun <T> snap() = androidx.compose.animation.core.snap<T>()
