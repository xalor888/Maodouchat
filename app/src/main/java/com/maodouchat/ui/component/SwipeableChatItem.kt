package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.SwipeArchiveColor
import com.maodouchat.ui.theme.SwipeDeleteColor
import com.maodouchat.ui.theme.SwipeMuteColor
import com.maodouchat.ui.theme.SwipePinColor
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipeable chat-list row (WeChat / iOS style).
 *
 * - Swipe left → reveal Mute / Archive / Delete (tap to fire; full swipe = delete)
 * - Swipe right → reveal Pin (tap or release past threshold)
 * - Settles open on partial swipe so actions stay tappable
 */
@Composable
fun SwipeableChatItem(
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isArchived: Boolean = false,
    onPin: () -> Unit = {},
    onMute: () -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidthPx = with(density) { 80.dp.toPx() }
    val openRightPx = actionWidthPx * 3f
    val openLeftPx = actionWidthPx
    val settleThreshold = actionWidthPx * 0.45f
    val fullDeleteThreshold = openRightPx * 0.92f

    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    var dragStart by remember { mutableFloatStateOf(0f) }
    var settledOpen by remember { mutableStateOf(false) }
    var crossedSettleThreshold by remember { mutableStateOf(false) }
    // 9.160：拖拽期间每个像素回调都会 launch 一次 snapTo——帧卡顿/重排时排队中的
    // 陈旧 snapTo 会在 settle/close 动画开始后回写旧偏移，行视觉上卡在半开状态。
    // 用 Job 引用取消上一份，保证同一时刻只有一个突变在途且收尾动画不被抢占。
    var dragSnapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun close() {
        settleJob?.cancel()
        settleJob = scope.launch {
            offset.animateTo(0f, motion.springSpec(dampingRatio = 0.86f, stiffness = 420f))
            settledOpen = false
        }
    }

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            offset.animateTo(target, motion.springSpec(dampingRatio = 0.86f, stiffness = 420f))
            settledOpen = target != 0f
        }
    }

    fun fireAndClose(action: () -> Unit) {
        com.maodouchat.util.HapticGate.perform(context, haptic, HapticFeedbackType.LongPress)
        action()
        close()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(isPinned, isMuted, isArchived) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStart = offset.value
                    },
                    onDragEnd = {
                        val x = offset.value
                        when {
                            x <= -fullDeleteThreshold -> fireAndClose(onDelete)
                            x < -settleThreshold -> settle(-openRightPx)
                            x > settleThreshold -> settle(openLeftPx)
                            else -> close()
                        }
                        crossedSettleThreshold = false
                    },
                    onDragCancel = {
                        crossedSettleThreshold = false
                        close()
                    }
                ) { _, dragAmount ->
                    val next = (offset.value + dragAmount).coerceIn(-openRightPx * 1.08f, openLeftPx * 1.08f)
                    dragSnapJob?.cancel()
                    dragSnapJob = scope.launch { offset.snapTo(next) }
                    // Light tick when the drag first crosses the settle threshold.
                    val crossed = abs(next) >= settleThreshold
                    if (crossed && !crossedSettleThreshold) {
                        com.maodouchat.util.HapticGate.perform(context, haptic, HapticFeedbackType.TextHandleMove)
                    }
                    crossedSettleThreshold = crossed
                }
            }
    ) {
        // Right-side actions (swipe left)
        if (offset.value < 0f) {
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwipeActionButton(
                    icon = if (isMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    label = stringResource(if (isMuted) R.string.swipe_unmute else R.string.swipe_mute),
                    color = SwipeMuteColor,
                    progress = ((-offset.value) / actionWidthPx).coerceIn(0f, 1f),
                    onClick = { fireAndClose(onMute) }
                )
                SwipeActionButton(
                    icon = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    label = stringResource(if (isArchived) R.string.swipe_unarchive else R.string.swipe_archive),
                    color = SwipeArchiveColor,
                    progress = (((-offset.value) - actionWidthPx) / actionWidthPx).coerceIn(0f, 1f),
                    onClick = { fireAndClose(onArchive) }
                )
                SwipeActionButton(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.swipe_delete),
                    color = SwipeDeleteColor,
                    progress = (((-offset.value) - actionWidthPx * 2f) / actionWidthPx).coerceIn(0f, 1f),
                    highlight = -offset.value >= fullDeleteThreshold,
                    onClick = { fireAndClose(onDelete) }
                )
            }
        }

        // Left-side pin (swipe right)
        if (offset.value > 0f) {
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwipeActionButton(
                    icon = Icons.Outlined.PushPin,
                    label = stringResource(if (isPinned) R.string.swipe_unpin else R.string.swipe_pin),
                    color = SwipePinColor,
                    progress = (offset.value / actionWidthPx).coerceIn(0f, 1f),
                    onClick = { fireAndClose(onPin) }
                )
            }
        }

        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offset.value }
                .clickable(enabled = settledOpen && abs(offset.value) > 1f) { close() }
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    progress: Float,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val alpha = progress.coerceIn(0f, 1f)
    val scale = 0.72f + 0.28f * alpha
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(if (highlight) color.copy(alpha = 0.92f) else color)
            .clickable(enabled = alpha > 0.35f, onClick = onClick)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
