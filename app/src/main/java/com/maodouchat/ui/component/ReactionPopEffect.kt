package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bouncy spring reaction tap effect.
 *
 * Scale: 0 → 1.2 → 1.0 with damping=0.4, stiffness=500.
 * Optional particle burst on tap. Use as an overlay on reaction buttons.
 */
@Composable
fun ReactionPopEffect(
    triggered: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF007AFF),
    particleCount: Int = 8,
    onFinished: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    val scale = remember { Animatable(0f) }
    val particles = remember { Animatable(0f) }

    LaunchedEffect(triggered, motion.animationsEnabled) {
        if (!triggered) {
            scale.snapTo(0f)
            return@LaunchedEffect
        }
        if (!motion.animationsEnabled) {
            scale.snapTo(1f)
            onFinished()
            return@LaunchedEffect
        }
        scale.snapTo(0f)
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.4f,
                stiffness = 500f
            )
        )
        particles.snapTo(0f)
        particles.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.6f,
                stiffness = 300f
            )
        )
        onFinished()
    }

    Box(modifier = modifier) {
        // Particle burst
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val pProgress = particles.value
            if (pProgress > 0.01f) {
                repeat(particleCount) { i ->
                    val angle = (2.0 * Math.PI * i / particleCount).toFloat()
                    val radius = 28f + (i % 3) * 8f
                    val dist = radius * pProgress
                    val px = cx + cos(angle.toDouble()).toFloat() * dist
                    val py = cy + sin(angle.toDouble()).toFloat() * dist
                    val particleAlpha = (1f - pProgress).coerceIn(0f, 1f)
                    val dotRadius = (3f - 1.5f * pProgress).coerceAtLeast(0.8f)
                    drawCircle(
                        color = color.copy(alpha = particleAlpha * 0.7f),
                        radius = dotRadius,
                        center = Offset(px, py)
                    )
                }
            }
        }

        // Content with spring scale
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = scale.value.coerceIn(0f, 1f)
            }
        ) {
            content()
        }
    }
}
