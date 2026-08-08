package com.maodouchat.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.maodouchat.ui.theme.LocalMotionSettings

/**
 * Wrapper that adds smooth press animation to message bubbles.
 * Scale down on press, spring back on release.
 */
@Composable
fun AnimatedMessageBubble(
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && motion.animationsEnabled) 0.96f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.7f, stiffness = 400f),
        label = "bubblePressScale"
    )

    val elevation by animateFloatAsState(
        targetValue = if (isPressed && motion.animationsEnabled) 2f else 0f,
        animationSpec = motion.springSpec(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bubbleElevation"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Subtle shadow effect
                shadowElevation = elevation
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onPress?.invoke() },
                    onLongPress = { onLongPress?.invoke() }
                )
            }
    ) {
        content()
    }
}

/**
 * Simple press scale modifier for any composable.
 */
@Composable
fun Modifier.pressScale(
    onPressScale: Float = 0.96f,
    springDamping: Float = 0.7f,
    springStiffness: Float = 400f
): Modifier {
    val motion = LocalMotionSettings.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && motion.animationsEnabled) onPressScale else 1f,
        animationSpec = motion.springSpec(dampingRatio = springDamping, stiffness = springStiffness),
        label = "pressScale"
    )

    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
