package com.maodouchat.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed

/**
 * Smooth animated loading indicator with rotating dots.
 */
@Composable
fun AnimatedLoadingIndicator(
    modifier: Modifier = Modifier,
    dotCount: Int = 8,
    dotSize: Dp = 6.dp,
    color: androidx.compose.ui.graphics.Color = Primary,
    secondaryColor: androidx.compose.ui.graphics.Color = PrimaryFixed
) {
    val motion = LocalMotionSettings.current
    if (!motion.animationsEnabled) {
        Canvas(modifier = modifier.size(dotSize * 4)) {
            drawCircle(
                color = color,
                radius = dotSize.toPx() / 2,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "loadingIndicator")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier.size(dotSize * 4)) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.width / 2 - dotSize.toPx()

        for (i in 0 until dotCount) {
            val angle = Math.toRadians(360.0 / dotCount * i + rotation)
            val x = centerX + radius * kotlin.math.cos(angle).toFloat()
            val y = centerY + radius * kotlin.math.sin(angle).toFloat()

            val alpha = (i.toFloat() / dotCount).let { base ->
                val adjusted = (base + rotation / 360f) % 1f
                0.3f + 0.7f * adjusted
            }

            drawCircle(
                color = if (i % 2 == 0) color else secondaryColor,
                radius = dotSize.toPx() / 2 * (0.5f + 0.5f * alpha),
                center = Offset(x, y),
                alpha = alpha.coerceIn(0.3f, 1f)
            )
        }
    }
}

/**
 * Pulsing dot loading indicator - minimal and clean.
 */
@Composable
fun PulsingDotIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    spacing: Dp = 6.dp,
    color: androidx.compose.ui.graphics.Color = Primary
) {
    val motion = LocalMotionSettings.current
    if (!motion.animationsEnabled) {
        Canvas(modifier = modifier.size(dotSize * 3 + spacing * 2)) {
            repeat(3) {
                drawCircle(
                    color = color,
                    radius = dotSize.toPx() / 2,
                    center = Offset(
                        x = it * (dotSize.toPx() + spacing.toPx()) + dotSize.toPx() / 2,
                        y = size.height / 2
                    )
                )
            }
        }
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulsingDot")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = motion.duration(1200), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsingPhase"
    )

    Canvas(
        modifier = modifier.size(
            width = dotSize * 3 + spacing * 2,
            height = dotSize
        )
    ) {
        repeat(3) { index ->
            val sinVal = kotlin.math.sin(phase - index * 0.85f).coerceIn(-1f, 1f)
            val norm = (sinVal + 1f) / 2f
            val scale = 0.4f + 0.6f * norm
            val x = index * (dotSize.toPx() + spacing.toPx()) + dotSize.toPx() / 2
            drawCircle(
                color = color,
                radius = dotSize.toPx() / 2 * scale,
                center = Offset(x, size.height / 2),
                alpha = 0.35f + 0.65f * norm
            )
        }
    }
}

/**
 * Smooth wave loading indicator - three dots with wave animation.
 */
@Composable
fun WaveLoadingIndicator(
    modifier: Modifier = Modifier,
    waveHeight: Dp = 20.dp,
    barWidth: Dp = 4.dp,
    barCount: Int = 5,
    color: androidx.compose.ui.graphics.Color = Primary
) {
    val motion = LocalMotionSettings.current
    if (!motion.animationsEnabled) {
        Canvas(modifier = modifier.size(barWidth * barCount * 2, waveHeight)) {
            repeat(barCount) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(it * barWidth.toPx() * 2, waveHeight.toPx() / 3),
                    size = androidx.compose.ui.geometry.Size(barWidth.toPx(), waveHeight.toPx() / 3),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth.toPx() / 2)
                )
            }
        }
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waveLoading")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = motion.duration(1000), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(
        modifier = modifier.size(
            width = barWidth * barCount * 2,
            height = waveHeight
        )
    ) {
        val maxBarHeight = waveHeight.toPx()
        val minBarHeight = maxBarHeight * 0.25f

        repeat(barCount) { index ->
            val sinVal = kotlin.math.sin(phase - index * 0.7f).coerceIn(-1f, 1f)
            val norm = (sinVal + 1f) / 2f
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * norm
            val x = index * barWidth.toPx() * 2

            drawRoundRect(
                color = color,
                topLeft = Offset(x, (maxBarHeight - barHeight) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth.toPx(), barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth.toPx() / 2),
                alpha = 0.5f + 0.5f * norm
            )
        }
    }
}
