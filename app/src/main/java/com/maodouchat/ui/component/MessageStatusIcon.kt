package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.rememberMotionPulse

@Composable
fun MessageStatusIcon(
    status: MessageStatus,
    tint: Color = LocalSentBubbleContentSecondary.current,
) {
    val displayStatus = status
    // 状态切换时做轻量 spring，解释发送中→已送达→已读；系统关闭动画时瞬时到位
    val motion = LocalMotionSettings.current
    val targetScale = when (displayStatus) {
        MessageStatus.FAILED -> 1.08f
        MessageStatus.SENDING -> 0.92f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = motion.springSpec(dampingRatio = 0.55f, stiffness = 520f),
        label = "messageStatusScale"
    )
    val pulse by rememberMotionPulse(
        initialValue = 0.55f,
        targetValue = 1f,
        durationMillis = 900,
        label = "messageStatusSendingPulse",
        active = displayStatus == MessageStatus.SENDING,
        staticValue = 0.85f
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (displayStatus == MessageStatus.SENDING) pulse else 1f
            },
        contentAlignment = Alignment.Center
    ) {
        when (displayStatus) {
            MessageStatus.SENDING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = tint
                )
            }
            MessageStatus.SENT -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.message_status_sent),
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }
            MessageStatus.DELIVERED -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.message_status_delivered),
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }
            MessageStatus.READ -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.message_status_read),
                    tint = OnlineGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
            MessageStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = stringResource(R.string.message_status_failed),
                    tint = LocalChatPalette.current.unreadRed,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
