package com.maodouchat.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.SurfaceVariant

/**
 * Animated three-dot typing indicator (Telegram / iMessage style).
 *
 * Each dot scales up and down with a staggered delay, creating a smooth
 * wave-like bouncing effect. Respects the user's motion-scale settings.
 *
 * @param text Optional text label to show alongside the dots (e.g. "Alice is typing")
 * @param dotColor Color of the bouncing dots
 * @param modifier Modifier for the composable
 */
@Composable
fun TypingIndicator(
    text: String? = null,
    modifier: Modifier = Modifier,
    dotColor: Color = Primary
) {
    val motion = LocalMotionSettings.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = Primary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (motion.animationsEnabled) {
                val transition = rememberInfiniteTransition(label = "typing")
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
                    label = "typingPhase"
                )
                repeat(3) { index ->
                    val sinVal = kotlin.math.sin(phase - index * 0.85f).coerceIn(-1f, 1f)
                    val norm = (sinVal + 1f) / 2f
                    val s = 0.45f + 0.55f * norm
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer {
                                scaleX = s
                                scaleY = s
                                alpha = 0.4f + 0.6f * norm
                                translationY = -norm * 3.5f
                            }
                            .background(dotColor, CircleShape)
                    )
                }
            } else {
                // Reduced-motion: static dots, no infinite loop (avoids tween(0) flicker).
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer { alpha = 0.7f }
                            .background(dotColor, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * Compact inline typing dots without background — for use inside chat list
 * preview text where space is constrained.
 */
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
                        easing = androidx.compose.animation.core.LinearEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "inlineTypingPhase"
            )
            repeat(3) { index ->
                val sinVal = kotlin.math.sin(phase - index * 0.85f).coerceIn(-1f, 1f)
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
