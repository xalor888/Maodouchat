package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings

/**
 * Telegram / Nekogram 风格打字指示微气泡 (Typing presence indicator).
 *
 * 采用 AnimatedVisibility 弹性滑入/淡出，三点交错正弦波微跳跃动效。
 *
 * @param visible 是否显示正在输入指示器
 * @param modifier 外部容器修饰符
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
            fadeIn(androidx.compose.animation.core.snap())
        } else {
            slideInVertically(
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)
            ) { it / 2 } + fadeIn(
                spring(dampingRatio = 0.75f, stiffness = 400f)
            )
        },
        exit = if (!motion.animationsEnabled) {
            fadeOut(androidx.compose.animation.core.snap())
        } else {
            slideOutVertically(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)
            ) { it / 2 } + fadeOut(
                spring(dampingRatio = 0.8f, stiffness = 500f)
            )
        }
    ) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .width(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp).copy(alpha = 0.92f))
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(15.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = MaterialTheme.colorScheme.primary
            if (motion.animationsEnabled) {
                val transition = rememberInfiniteTransition(label = "typingPresence")
                val phase by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = motion.duration(1000),
                            easing = LinearEasing
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
                            .size(6.5.dp)
                            .graphicsLayer {
                                translationY = -norm * 4.5.dp.toPx()
                                val s = 0.8f + 0.35f * norm
                                scaleX = s
                                scaleY = s
                                alpha = 0.4f + 0.6f * norm
                            }
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            } else {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .graphicsLayer { alpha = 0.7f }
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
    }
}
