package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlinx.coroutines.launch

private const val OVERSCROLL_DAMPING = 0.35f
private const val MAX_OVERSCROLL = 80f

/**
 * Rubber-band overscroll Modifier for bottom sheets and other scrollable containers.
 *
 * When dragged past the boundary, applies exponential rubber-band resistance.
 * On release, springs back to zero with medium bouncy physics.
 *
 * Usage: `Modifier.elasticOverscroll(canOverscrollTop, canOverscrollBottom)`
 */
@Composable
fun Modifier.elasticOverscroll(
    canOverscrollTop: Boolean = false,
    canOverscrollBottom: Boolean = false
): Modifier {
    val motion = LocalMotionSettings.current
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }

    return this
        .graphicsLayer {
            translationY = offsetY.value
        }
        .pointerInput(canOverscrollTop, canOverscrollBottom, motion.animationsEnabled) {
            detectVerticalDragGestures(
                onDragEnd = {
                    scope.launch {
                        offsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = if (motion.animationsEnabled) {
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            } else {
                                snap()
                            }
                        )
                    }
                },
                onDragCancel = {
                    scope.launch {
                        offsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = if (motion.animationsEnabled) {
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            } else {
                                snap()
                            }
                        )
                    }
                },
                onVerticalDrag = { _, dragAmount ->
                    scope.launch {
                        val canDragUp = canOverscrollTop && dragAmount < 0
                        val canDragDown = canOverscrollBottom && dragAmount > 0
                        if (!canDragUp && !canDragDown) return@launch

                        val newOffset = (offsetY.value + dragAmount)
                            .coerceIn(-MAX_OVERSCROLL, MAX_OVERSCROLL)
                            .let { value ->
                                // Rubber-band: past boundary, dampen exponentially
                                val absVal = kotlin.math.abs(value)
                                if (absVal > MAX_OVERSCROLL * 0.6f) {
                                    val dampened = MAX_OVERSCROLL * 0.6f +
                                        (absVal - MAX_OVERSCROLL * 0.6f) * OVERSCROLL_DAMPING
                                    if (value > 0) dampened else -dampened
                                } else value
                            }
                        offsetY.snapTo(newOffset)
                    }
                }
            )
        }
}

/**
 * Composable wrapper that applies [elasticOverscroll] and resets on configuration change.
 *
 * Use this on bottom sheet content that should have rubber-band overscroll.
 */
@Composable
fun ElasticOverscrollBox(
    modifier: Modifier = Modifier,
    canOverscrollTop: Boolean = false,
    canOverscrollBottom: Boolean = true,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.elasticOverscroll(canOverscrollTop, canOverscrollBottom)
    ) {
        content()
    }
}
