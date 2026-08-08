@file:Suppress("DEPRECATION")

package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.SurfaceVariant
import com.maodouchat.util.HapticGate
import kotlin.math.roundToInt

/**
 * A lightweight pull-to-refresh wrapper.
 *
 * Supports a drag-down gesture on the content area. When the drag exceeds
 * [threshold], [onRefresh] is called and a loading indicator spins until
 * [isRefreshing] becomes false.
 *
 * @param isRefreshing Whether a refresh is currently in progress
 * @param onRefresh Callback when the pull threshold is exceeded
 * @param threshold Pull distance (in dp) required to trigger refresh
 * @param content The scrollable content to wrap
 */
@Composable
fun PullToRefreshLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    threshold: Float = 80f,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val motion = LocalMotionSettings.current
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current
    val thresholdPx = with(density) { threshold.dp.toPx() }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var refreshTriggered by remember { mutableStateOf(false) }

    // Smoothly animate offset back to 0 when released or refreshing
    val animatedOffset by animateFloatAsState(
        targetValue = if (isRefreshing) thresholdPx else if (refreshTriggered) 0f else dragOffset,
        animationSpec = motion.tweenSpec(MotionTokens.Emphasized),
        label = "pullOffset"
    )

    // Calculate progress for the indicator
    val progress = (animatedOffset / thresholdPx).coerceIn(0f, 1f)
    val indicatorRotation = progress * 360f

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Content with vertical offset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, animatedOffset.roundToInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            if (dragOffset >= thresholdPx && !isRefreshing) {
                                refreshTriggered = true
                                HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                onRefresh()
                            }
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragOffset = 0f
                        },
                        onVerticalDrag = { change, delta ->
                            // Only allow pulling down when at the top of content
                            // and not already refreshing
                            if (!isRefreshing && dragOffset >= 0) {
                                dragOffset = (dragOffset + delta * 0.5f).coerceAtLeast(0f)
                            }
                            change.consume()
                        }
                    )
                }
        ) {
            content()
        }

        // Refresh indicator at the top
        if (animatedOffset > 0.5f || isRefreshing) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .offset { IntOffset(0, (animatedOffset - with(density) { 32.dp.toPx() }).roundToInt().coerceAtLeast(0)) }
                    .graphicsLayer {
                        alpha = progress
                    }
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = Primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                rotationZ = indicatorRotation
                            }
                    )
                }
            }
        }

        // Reset trigger flag when refresh completes
        if (!isRefreshing && refreshTriggered) {
            refreshTriggered = false
        }
    }
}
