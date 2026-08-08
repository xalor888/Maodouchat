package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Telegram 风格消息碎裂动效。
 *
 * 触发时使用消息气泡在根布局中的真实位置和尺寸，把气泡区域切成一组小碎片，
 * 每个碎片从原本所在位置向外扩散、旋转、缩小并渐隐。动画结束后再执行删除/撤回逻辑。
 */
class ParticleState(
    val targetId: String,
    private val bounds: IntOffset,
    private val boxSize: IntSize,
    private val bubbleColor: Color
) {
    private val particles: List<Particle> = buildParticles()

    private fun buildParticles(): List<Particle> {
        val columns = 9
        val rows = 5
        val cellW = (boxSize.width.coerceAtLeast(1) / columns.toFloat()).coerceAtLeast(2f)
        val cellH = (boxSize.height.coerceAtLeast(1) / rows.toFloat()).coerceAtLeast(2f)
        val centerX = bounds.x + boxSize.width / 2f
        val centerY = bounds.y + boxSize.height / 2f

        return buildList {
            repeat(rows) { row ->
                repeat(columns) { column ->
                    val x = bounds.x + cellW * (column + 0.5f) + Random.nextFloat() * cellW * 0.5f - cellW * 0.25f
                    val y = bounds.y + cellH * (row + 0.5f) + Random.nextFloat() * cellH * 0.5f - cellH * 0.25f
                    val angle = kotlin.math.atan2((y - centerY).toDouble(), (x - centerX).toDouble()).toFloat()
                    val spread = 42f + Random.nextFloat() * 78f
                    add(
                        Particle(
                            origin = Offset(x, y),
                            velocity = Offset(cos(angle) * spread + Random.nextFloat() * 40f - 20f, sin(angle) * spread - 80f - Random.nextFloat() * 50f),
                            size = minOf(cellW, cellH) * (0.65f + Random.nextFloat() * 0.85f),
                            delay = Random.nextFloat() * 0.18f,
                            spin = (Random.nextFloat() * 2f - 1f) * PI.toFloat()
                        )
                    )
                }
            }
        }
    }

    fun draw(drawScope: DrawScope, progress: Float) {
        particles.forEach { particle ->
            val localProgress = ((progress - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
            if (localProgress <= 0f) return@forEach

            val eased = 1f - (1f - localProgress) * (1f - localProgress)
            val gravity = 160f * localProgress * localProgress
            val drift = particle.velocity * eased
            val center = Offset(
                x = particle.origin.x + drift.x,
                y = particle.origin.y + drift.y + gravity
            )
            val alpha = (1f - localProgress).coerceIn(0f, 1f)
            val radius = (particle.size * (1f - localProgress * 0.45f)).coerceAtLeast(0.8f)
            val warmTint = Color(0xFFFFD166)
            val color = if (particle.spin > 0f) bubbleColor else bubbleColor.copy(
                red = bubbleColor.red * 0.82f + warmTint.red * 0.18f,
                green = bubbleColor.green * 0.82f + warmTint.green * 0.18f,
                blue = bubbleColor.blue * 0.82f + warmTint.blue * 0.18f
            )

            drawScope.drawCircle(
                color = color.copy(alpha = alpha * 0.92f),
                radius = radius,
                center = center
            )
        }
    }

    private data class Particle(
        val origin: Offset,
        val velocity: Offset,
        val size: Float,
        val delay: Float,
        val spin: Float
    )
}

@Composable
fun ParticleDeleteEffect(
    particleStates: List<ParticleState>,
    onFinished: () -> Unit
) {
    val progress = remember(particleStates) { Animatable(0f) }
    val motion = LocalMotionSettings.current

    LaunchedEffect(particleStates, motion.animationsEnabled, motion.durationScale) {
        if (particleStates.isEmpty()) return@LaunchedEffect
        progress.snapTo(0f)
        if (!motion.animationsEnabled) {
            progress.snapTo(1f)
            onFinished()
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = motion.duration(MotionTokens.Particle),
                easing = FastOutSlowInEasing
            )
        )
        onFinished()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val p = progress.value
        particleStates.forEach { it.draw(this, p) }
    }
}
