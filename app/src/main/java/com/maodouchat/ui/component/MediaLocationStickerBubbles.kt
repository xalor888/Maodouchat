package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.rememberMotionPulse
import java.util.Locale

/**
 * 位置与贴纸消息气泡（G129 从 `MediaMessageBubbles.kt` 拆出，原 186 行）。
 *
 * `LocationBubble`（静态地图占位 + 坐标 + 「导航」按钮）与
 * `StickerBubble`（贴纸大图 + 发送态）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

// ─── LocationBubble ───
@Composable
internal fun LocationBubble(
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
    val palette = LocalChatPalette.current
    val payload = presentation.location
    if (payload == null) {
        SystemMessageRenderer(stringResource(R.string.message_location_invalid), modifier)
        return
    }
    val context = LocalContext.current
    val locationLabel = if (payload.label.isBlank() || payload.label == "当前位置") {
        stringResource(R.string.message_location_current)
    } else {
        payload.label
    }
    var liveNow by remember(payload.sessionId, payload.liveUntil) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(payload.sessionId, payload.live, payload.liveUntil) {
        val until = payload.liveUntil
        if (payload.live && until != null) {
            while (liveNow < until) {
                kotlinx.coroutines.delay((until - liveNow).coerceIn(250L, 1_000L))
                liveNow = System.currentTimeMillis()
            }
        }
    }
    val liveActive = payload.live && (payload.liveUntil == null || payload.liveUntil > liveNow)
    val liveRemain = if (liveActive) {
        com.maodouchat.util.LiveLocationPolicy.formatRemaining(
            com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(payload.liveUntil, liveNow)
        )
    } else ""
    val displayLocationLabel = if (liveActive) {
        stringResource(R.string.message_location_live_active, liveRemain)
    } else locationLabel
    val pulse by rememberMotionPulse(
        initialValue = 0.72f,
        targetValue = 1f,
        durationMillis = 1_100,
        label = "locationPulse"
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
        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .widthIn(max = 260.dp)
                    .clip(if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent else com.maodouchat.ui.theme.LocalBubbleShapes.current.received)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .clickable {
                        val label = android.net.Uri.encode(payload.label)
                        val uri = android.net.Uri.parse("geo:${payload.latitude},${payload.longitude}?q=${payload.latitude},${payload.longitude}($label)")
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(104.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = if (isOwnMessage) 0.22f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(58.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape))
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = if (isOwnMessage) Color.White else Primary, modifier = Modifier.size(42.dp))
                }
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(displayLocationLabel, style = MaterialTheme.typography.bodyLarge, color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", payload.latitude, payload.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
                    )
                    payload.accuracyMeters?.let {
                        Text(stringResource(R.string.message_location_accuracy, it.toInt().coerceAtLeast(1)), style = MaterialTheme.typography.labelSmall, color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 4.dp)) {
                if (presentation.meta.silent) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = LocalChatPalette.current.textHint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                // 1.51：点击已读状态图标打开阅读详情（仅自己消息可看）
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
