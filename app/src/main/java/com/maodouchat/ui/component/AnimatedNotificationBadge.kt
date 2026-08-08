package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.UnreadRed

/**
 * Animated notification badge with bounce-in effect.
 * Shows count or dot-only mode.
 */
@Composable
fun AnimatedNotificationBadge(
    count: Int,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
    color: Color = UnreadRed
) {
    val motion = LocalMotionSettings.current
    val scale = remember { Animatable(0f) }

    LaunchedEffect(count) {
        if (motion.animationsEnabled && count > 0) {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.45f,
                    stiffness = 600f
                )
            )
        } else {
            scale.snapTo(if (count > 0) 1f else 0f)
        }
    }

    val isVisible = count > 0 || showDot

    AnimatedVisibility(
        visible = isVisible,
        enter = scaleIn(
            animationSpec = spring(
                dampingRatio = 0.45f,
                stiffness = 600f
            )
        ) + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (showDot && count == 0) 8.dp else 20.dp)
                .clip(CircleShape)
                .background(color)
                .padding(horizontal = if (showDot && count == 0) 0.dp else 4.dp)
        ) {
            if (count > 0) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Pulsing dot indicator for online status or active state.
 */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = Color.Green,
    size: androidx.compose.ui.unit.Dp = 8.dp
) {
    val motion = LocalMotionSettings.current
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulsingDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = motion.duration(1000),
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulsingAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        if (motion.animationsEnabled) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha * 0.3f))
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.6f)
                .clip(CircleShape)
                .background(color)
        )
    }
}
