package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette

// ─── StickerBubble ───
@Composable
internal fun StickerBubble(
    message: Message,
    presentation: MessagePresentation,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String?,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?,
    currentUserId: String?,
    onReactionClick: ((String) -> Unit)? = null,
    /** 1.70：点击已读状态图标打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null,
    showStatusIcon: Boolean = true
) {
    var entered by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.55f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
        label = "stickerScale"
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            if (showAvatar) Avatar(name = senderName ?: "?", size = AvatarSize.SM, modifier = Modifier.padding(bottom = 4.dp))
            else Spacer(modifier = Modifier.width(36.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start,
            modifier = Modifier.captureBubbleBounds(onBoundsMeasured)
        ) {
            Text(
                text = presentation.body,
                fontSize = 72.sp,
                lineHeight = 78.sp,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                if (isOwnMessage && showStatusIcon) {
                    Spacer(modifier = Modifier.width(4.dp))
                    if (onStatusClick != null) {
                        Box(modifier = Modifier.clickable { onStatusClick(message) }) {
                            MessageStatusIcon(message.status)
                        }
                    } else {
                        MessageStatusIcon(message.status)
                    }
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}
