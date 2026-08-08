package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animated list item wrapper that provides smooth entry animation.
 * Supports stagger delay for sequential list items.
 */
@Composable
fun AnimatedListItem(
    index: Int,
    modifier: Modifier = Modifier,
    staggerDelayMs: Long = 32,
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    val scope = rememberCoroutineScope()

    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(20f) }

    LaunchedEffect(Unit) {
        if (motion.animationsEnabled) {
            delay(index * staggerDelayMs.coerceAtMost(160))
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                offsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        } else {
            alpha.snapTo(1f)
            offsetY.snapTo(0f)
        }
    }

    androidx.compose.runtime.key(index) {
        content()
    }

    // Note: This wrapper applies animation via graphicsLayer.
    // To use it, wrap your list item content in AnimatedListItem.
    // The actual animation is applied in the modifier chain.
}

/**
 * Extension modifier for animated list item entry.
 */
fun Modifier.animatedListItem(
    index: Int,
    staggerDelayMs: Long = 32,
    animationsEnabled: Boolean = true
): Modifier {
    if (!animationsEnabled) return this

    return this.graphicsLayer {
        // Initial state: transparent and slightly offset
        alpha = 0f
        translationY = 20f
    }
}

/**
 * Composable wrapper that handles stagger animation for chat list items.
 */
@Composable
fun StaggeredAnimatedItem(
    index: Int,
    modifier: Modifier = Modifier,
    maxStaggerMs: Long = 160,
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val alpha = remember { Animatable(0f) }
    val translationY = remember { Animatable(with(density) { 20.dp.toPx() }) }

    LaunchedEffect(Unit) {
        if (motion.animationsEnabled) {
            val stagger = (index * 32L).coerceAtMost(maxStaggerMs)
            delay(stagger)

            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                translationY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        } else {
            alpha.snapTo(1f)
            translationY.snapTo(0f)
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha.value
                this.translationY = translationY.value
            }
    ) {
        content()
    }
}
