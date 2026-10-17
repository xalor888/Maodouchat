package com.maodouchat.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary
import kotlin.math.sin

// 无背景的紧凑版输入中圆点，用在聊天列表预览行里。
@Composable
fun InlineTypingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = Primary
) {
    val motion = LocalMotionSettings.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (motion.animationsEnabled) {
            val transition = rememberInfiniteTransition(label = "inlineTyping")
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
                label = "inlineTypingPhase"
            )
            repeat(3) { index ->
                val sinVal = sin(phase - index * 0.85f).coerceIn(-1f, 1f)
                val norm = (sinVal + 1f) / 2f
                val s = 0.5f + 0.5f * norm
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .graphicsLayer {
                            scaleX = s
                            scaleY = s
                            alpha = 0.4f + 0.6f * norm
                            translationY = -norm * 2f
                        }
                        .background(dotColor, CircleShape)
                )
            }
        } else {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .graphicsLayer { alpha = 0.7f }
                        .background(dotColor, CircleShape)
                )
            }
        }
    }
}
