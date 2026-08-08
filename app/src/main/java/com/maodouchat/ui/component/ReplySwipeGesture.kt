package com.maodouchat.ui.component

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SWIPE_THRESHOLD = 120f
private const val MAX_SWIPE = 200f
private const val RUBBER_BAND_FACTOR = 0.4f

/**
 * Wraps content to enable right-to-left swipe-to-reply.
 *
 * Rubber-band resistance past threshold, reply arrow reveal, haptic feedback on threshold,
 * and spring back on release. [onSwipeReply] fires when swipe exceeds threshold.
 */
@Composable
fun ReplySwipeGesture(
    onSwipeReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    val offsetX = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableFloatStateOf(0f) }
    var replyTriggered by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Reply arrow indicator behind content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            contentAlignment = Alignment.CenterStart
        ) {
            val arrowAlpha = (offsetX.value / SWIPE_THRESHOLD).coerceIn(0f, 1f)
            if (arrowAlpha > 0.01f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = stringResource(R.string.message_reply),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = arrowAlpha),
                    modifier = Modifier
                        .offset(x = 12.dp)
                        .graphicsLayer {
                            scaleX = arrowAlpha
                            scaleY = arrowAlpha
                        }
                )
            }
        }

        // Swipeable content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(motion.animationsEnabled) {
                    if (!motion.animationsEnabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (abs(offsetX.value) > SWIPE_THRESHOLD && !replyTriggered) {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    replyTriggered = true
                                    0f
                                } else {
                                    0f
                                }
                                offsetX.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                if (replyTriggered) {
                                    onSwipeReply()
                                    replyTriggered = false
                                }
                                hasTriggeredHaptic = 0f
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                hasTriggeredHaptic = 0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount)
                                    .coerceIn(-MAX_SWIPE, 0f)
                                    // Rubber-band past threshold
                                    .let { value ->
                                        if (value < -SWIPE_THRESHOLD) {
                                            val excess = abs(value) - SWIPE_THRESHOLD
                                            -SWIPE_THRESHOLD - excess * RUBBER_BAND_FACTOR
                                        } else value
                                    }
                                offsetX.snapTo(newOffset)
                            }
                            // Haptic feedback at threshold
                            if (abs(offsetX.value) > SWIPE_THRESHOLD && hasTriggeredHaptic == 0f) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                hasTriggeredHaptic = 1f
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
