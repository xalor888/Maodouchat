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
            repeat(3) { index ->
                TypingDot(
                    index = index,
                    animationsEnabled = motion.animationsEnabled
                )
            }
        }
    }
}

@Composable
private fun TypingDot(index: Int, animationsEnabled: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "typingDot$index")

    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotOffset$index"
    )

    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha$index"
    )

    // offset/scale/alpha 延后到放置与绘制阶段读取动画状态：组合期不读 offsetY/dotAlpha，
    // 动画每帧只触发 placement/draw，不再重组整个 TypingDot。
    Box(
        modifier = Modifier
            .size(7.dp)
            .offset { IntOffset(0, if (animationsEnabled) offsetY.dp.roundToPx() else 0) }
            .graphicsLayer {
                val s = if (animationsEnabled) 1f + offsetY / 12f else 1f
                scaleX = s
                scaleY = s
                alpha = if (animationsEnabled) dotAlpha else 0.7f
            }
            .clip(RoundedCornerShape(3.5.dp))
            .background(Primary)
    )
}

private fun <T> snap() = androidx.compose.animation.core.snap<T>()
