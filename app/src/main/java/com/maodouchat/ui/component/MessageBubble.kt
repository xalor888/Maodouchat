package com.maodouchat.ui.component

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.maodouchat.network.TokenManager
import com.maodouchat.network.ApiService
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import com.maodouchat.util.LinkPreviewPolicy
import com.maodouchat.util.LinkPreviewPreferences
import com.maodouchat.util.LinkPreviewRepository
import com.maodouchat.util.MediaCache
import com.maodouchat.ui.theme.Error
import androidx.compose.ui.graphics.Brush
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.TextWhite
import com.maodouchat.ui.theme.TextWhiteSecondary
import com.maodouchat.ui.theme.UnreadRed
import java.util.Locale

// 气泡形状
private val SentBubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val ReceivedBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
private val SystemBubbleShape = RoundedCornerShape(16.dp)

// 语音波形高度常量
private val WaveHeights = listOf(0.3f, 0.7f, 1f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f)

/**
 * 消息气泡组件
 *
 * @param message 消息数据
 * @param isOwnMessage 是否是自己发送的消息
 * @param showAvatar 是否显示头像（连续消息中可隐藏）
 * @param senderName 发送者名称（接收消息时显示）
 * @param onImageClick 图片点击回调（用于全屏查看）
 * @param onVideoClick 视频点击回调（用于播放）
 */
@Composable
fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
    senderName: String? = null,
    /** 0.65 新功能：发送者在群内角色（OWNER/ADMIN/MEMBER），群聊时名字旁渲染徽章。 */
    memberRole: String? = null,
    isGroupChat: Boolean = false,
    onImageClick: ((Message) -> Unit)? = null,
    onVideoClick: ((Message) -> Unit)? = null,
    mentionedUserIds: List<String> = emptyList(),
    replyToPreview: ReplyPreview? = null,
    onReply: ((Message) -> Unit)? = null,
    onReplyPreviewClick: ((Message) -> Unit)? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    onFileClick: ((Message) -> Unit)? = null,
    voiceTranscript: String? = null,
    isVoiceTranscribing: Boolean = false,
    onRequestVoiceTranscript: ((String) -> Unit)? = null,
    onCopyVoiceTranscript: ((String) -> Unit)? = null,
    translationText: String? = null,
    isTranslating: Boolean = false,
    isAiAssisted: Boolean = false,
    currentUserId: String? = null,
    fileTransferProgress: Float? = null,
    fileTransferState: String? = null,
    fileTransferError: String? = null,
    onPauseFileTransfer: ((String) -> Unit)? = null,
    onResumeFileTransfer: ((String) -> Unit)? = null,
    onCancelFileTransfer: ((String) -> Unit)? = null,
    onRequestMediaAttachment: ((String) -> Unit)? = null,
    mediaDownloadFailed: Boolean = false,
    safetyWarning: String? = null,
    onDismissSafety: (() -> Unit)? = null,
    onReactionClick: ((String) -> Unit)? = null,
    onPollVote: ((String, Int) -> Unit)? = null,
    secretChatId: String? = null,
    onViewOnceOpened: ((String) -> Unit)? = null,
    onRevealSpoiler: ((String) -> Unit)? = null,
    onInlineKeyboardClick: ((String, String) -> Unit)? = null,
    /** 1.17：点击消息内联系人名片 → 打开该用户资料。 */
    onContactCardClick: ((String) -> Unit)? = null,
    /** 1.44：点击消息发送者名称 → 打开其资料。 */
    onSenderClick: ((String) -> Unit)? = null,
    /** 1.51：点击已读状态图标（✓✓）→ 打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null
) {
    when (message.type) {
        MessageType.NUDGE -> {
            // Hoist format templates via stringResource so locale changes recompose correctly.
            val youNudgedFmt = stringResource(R.string.chat_nudge_you_nudged)
            val theyNudgedYouFmt = stringResource(R.string.chat_nudge_they_nudged_you)
            val theyNudgedTargetFmt = stringResource(R.string.chat_nudge_they_nudged_target)
            val templates = NudgeDisplayPolicy.Templates(
                youNudged = { target -> youNudgedFmt.format(target) },
                theyNudgedYou = { sender -> theyNudgedYouFmt.format(sender) },
                theyNudgedTarget = { sender, target -> theyNudgedTargetFmt.format(sender, target) }
            )
            val display = NudgeDisplayPolicy.displayText(
                isOwnMessage = isOwnMessage,
                storedContent = message.content,
                senderDisplayName = senderName.orEmpty(),
                isDirectChat = !isGroupChat,
                templates = templates
            )
            SystemMessageBubble(display, modifier)
        }
        MessageType.SYSTEM -> SystemMessageBubble(message.content, modifier)
        MessageType.REVOKED -> SystemMessageBubble(stringResource(R.string.chat_message_revoked_placeholder), modifier)
        MessageType.MARKDOWN, MessageType.TEXT -> TextBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, mentionedUserIds, replyToPreview, onReply,
            onReplyPreviewClick, onBoundsMeasured, translationText, isTranslating, isAiAssisted, currentUserId, safetyWarning, onDismissSafety,
            onReactionClick, onPollVote, secretChatId, onInlineKeyboardClick, onContactCardClick, onSenderClick, onStatusClick
        )
        MessageType.IMAGE -> ImageBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onImageClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId, onViewOnceOpened, onRevealSpoiler
        )
        MessageType.GIF -> ImageBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onImageClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId, onViewOnceOpened, onRevealSpoiler
        )
        MessageType.STICKER -> StickerBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured, currentUserId, onReactionClick, onStatusClick
        )
        MessageType.LOCATION -> LocationBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured, currentUserId, onReactionClick, onStatusClick
        )
        MessageType.VOICE -> VoiceBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured,
            voiceTranscript, isVoiceTranscribing, onRequestVoiceTranscript, onCopyVoiceTranscript,
            fileTransferProgress, fileTransferState,
            fileTransferError, onPauseFileTransfer, onResumeFileTransfer,
            onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, onStatusClick
        )
        MessageType.VIDEO -> VideoBubble(
            message, isOwnMessage, modifier, showAvatar, senderName, onVideoClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId
        )
        MessageType.FILE -> FileBubble(
            message,
            isOwnMessage,
            modifier,
            showAvatar,
            senderName,
            onBoundsMeasured,
            onFileClick,
            fileTransferProgress,
            fileTransferState,
            fileTransferError,
            onPauseFileTransfer,
            onResumeFileTransfer,
            onCancelFileTransfer,
            currentUserId,
            onReactionClick,
            onStatusClick
        )
        MessageType.SK_DIST -> Unit
    }
}

data class ReplyPreview(val senderName: String, val preview: String)

@Composable
private fun ReactionSummaryRow(
    message: Message,
    currentUserId: String?,
    isOwnMessage: Boolean,
    onReactionClick: ((String) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    if (message.reactions.isEmpty()) return
    val grouped = remember(message.reactions) {
        message.reactions.groupBy { it.emoji }
            .map { (emoji, reactions) -> emoji to reactions.sortedBy { it.reactedAt } }
            .sortedBy { (_, reactions) -> reactions.firstOrNull()?.reactedAt ?: 0L }
    }
    val visible = grouped.take(24)
    val overflow = (grouped.size - visible.size).coerceAtLeast(0)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        visible.forEach { (emoji, reactions) ->
            val reactedByMe = currentUserId != null && reactions.any { it.userId == currentUserId }
            Text(
                text = "$emoji ${reactions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = if (reactedByMe) Primary else if (isOwnMessage) TextWhite else OnSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            reactedByMe -> Primary.copy(alpha = 0.14f)
                            isOwnMessage -> Color.White.copy(alpha = 0.18f)
                            else -> palette.chatInputBackground
                        }
                    )
                    .then(
                        if (onReactionClick != null) {
                            Modifier.clickable { onReactionClick(emoji) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOwnMessage) TextWhite else TextHint,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isOwnMessage) Color.White.copy(alpha = 0.18f)
                        else palette.chatInputBackground
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun LocationBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String?,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?,
    currentUserId: String?,
    onReactionClick: ((String) -> Unit)? = null,
    /** 1.70：点击已读状态图标打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    val payload = remember(message.content) { message.parsedLocation() }
    if (payload == null) {
        SystemMessageBubble(stringResource(R.string.message_location_invalid), modifier)
        return
    }
    val context = LocalContext.current
    val locationLabel = if (payload.label.isBlank() || payload.label == "当前位置") {
        stringResource(R.string.message_location_current)
    } else {
        payload.label
    }
    var liveNow by remember(payload.sessionId, payload.liveUntil) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(payload.sessionId, payload.live, payload.liveUntil) {
        val until = payload.liveUntil
        if (payload.live && until != null) {
            while (liveNow < until) {
                kotlinx.coroutines.delay((until - liveNow).coerceIn(250L, 1_000L))
                liveNow = System.currentTimeMillis()
            }
        }
    }
    val liveActive = com.maodouchat.util.LiveLocationPolicy.isLive(payload, liveNow)
    val liveRemain = if (liveActive) {
        com.maodouchat.util.LiveLocationPolicy.formatRemaining(
            com.maodouchat.util.LiveLocationPolicy.remainingMs(payload, liveNow)
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
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
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
                    modifier = Modifier.fillMaxWidth().height(104.dp).background(Primary.copy(alpha = if (isOwnMessage) 0.22f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(58.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(Primary.copy(alpha = 0.14f), CircleShape))
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = if (isOwnMessage) Color.White else Primary, modifier = Modifier.size(42.dp))
                }
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(displayLocationLabel, style = MaterialTheme.typography.bodyLarge, color = if (isOwnMessage) TextWhite else OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", payload.latitude, payload.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) TextWhiteSecondary else TextHint
                    )
                    payload.accuracyMeters?.let {
                        Text(stringResource(R.string.message_location_accuracy, it.toInt().coerceAtLeast(1)), style = MaterialTheme.typography.labelSmall, color = if (isOwnMessage) TextWhiteSecondary else TextHint)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 4.dp)) {
                if (message.parsedMeta().silent) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = TextHint)
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                // 1.51：点击已读状态图标打开阅读详情（仅自己消息可看）
                if (isOwnMessage) {
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

@Composable
private fun StickerBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String?,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?,
    currentUserId: String?,
    onReactionClick: ((String) -> Unit)? = null,
    /** 1.70：点击已读状态图标打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null
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
                text = message.parsedContent(),
                fontSize = 72.sp,
                lineHeight = 78.sp,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = TextHint)
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                if (isOwnMessage) {
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

private fun Modifier.captureBubbleBounds(onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?): Modifier {
    if (onBoundsMeasured == null) return this
    return onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        onBoundsMeasured(
            IntOffset(position.x.roundToInt(), position.y.roundToInt()),
            IntSize(coordinates.size.width, coordinates.size.height)
        )
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun TextBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String?,
    mentionedUserIds: List<String> = emptyList(),
    replyToPreview: ReplyPreview? = null,
    onReply: ((Message) -> Unit)? = null,
    onReplyPreviewClick: ((Message) -> Unit)? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    translationText: String? = null,
    isTranslating: Boolean = false,
    isAiAssisted: Boolean = false,
    currentUserId: String? = null,
    safetyWarning: String? = null,
    onDismissSafety: (() -> Unit)? = null,
    onReactionClick: ((String) -> Unit)? = null,
    onPollVote: ((String, Int) -> Unit)? = null,
    secretChatId: String? = null,
    onInlineKeyboardClick: ((String, String) -> Unit)? = null,
    /** 1.17：点击消息内联系人名片 → 打开该用户资料。 */
    onContactCardClick: ((String) -> Unit)? = null,
    /** 1.44：点击消息发送者名称 → 打开其资料。 */
    onSenderClick: ((String) -> Unit)? = null,
    /** 1.51：点击已读状态图标（✓✓）→ 打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null,
    /** 0.65：发送者群内角色（群主/管理员徽章，仅群聊显示）。 */
    memberRole: String? = null
) {
    val palette = LocalChatPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            // 接收方：左侧显示头像
            if (showAvatar) {
                Avatar(
                    name = senderName ?: "?",
                    size = AvatarSize.SM,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(36.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            // 发送者名称（接收方）——1.44：点击打开发送者资料
            if (!isOwnMessage && senderName != null && showAvatar) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextHint,
                        modifier = Modifier
                            .padding(start = 4.dp, bottom = 2.dp)
                            .clickable(enabled = onSenderClick != null) {
                                onSenderClick?.invoke(message.senderId)
                            }
                    )
                    // 0.65 新功能：群主/管理员徽章（仅群聊且角色明确时显示）
                    when (memberRole) {
                        "OWNER" -> RoleBadge(stringResource(R.string.chat_role_owner), owner = true)
                        "ADMIN" -> RoleBadge(stringResource(R.string.chat_role_admin), owner = false)
                        else -> Unit
                    }
                }
            }
            // 0.67 新功能：已转发标记（E2EE meta 内传输，密聊转发仅标记不露来源名）
            val forwardedFrom = message.parsedMeta().forwardedFrom
            if (forwardedFrom != null) {
                Text(
                    text = stringResource(R.string.message_forwarded_from, forwardedFrom),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Primary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }

            // 引用预览（如果有）
            if (replyToPreview != null) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.6f) else palette.chatInputBackground)
                        .then(if (onReplyPreviewClick != null) Modifier.clickable { onReplyPreviewClick(message) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.message_reply_preview, replyToPreview.senderName, replyToPreview.preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) TextWhiteSecondary else TextHint,
                        maxLines = 1
                    )
                }
            }

            // 气泡
            Column(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
                    .background(if (isOwnMessage) Brush.linearGradient(com.maodouchat.ui.theme.ChatBubbleColorPalette.gradient(LocalChatBubbleColor.current)) else Brush.linearGradient(listOf(palette.chatBubbleReceived, palette.chatBubbleReceived)))
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            ReceivedBubbleShape
                        ) else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (isAiAssisted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (isOwnMessage) TextWhiteSecondary else Primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.message_ai_assisted_shared),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwnMessage) TextWhiteSecondary else Primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                val parsedMeta = message.parsedMeta()
                val dice = com.maodouchat.util.GroupPlayPolicy.parseDice(message.parsedContent())
                if (dice != null) {
                    val (sides, value) = dice
                    Text(
                        text = "🎲 $value / $sides",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isOwnMessage) TextWhite else OnSurface
                    )
                    return@Column
                }
                val lucky = com.maodouchat.util.GroupPlayPolicy.parseLuckyDraw(message.parsedContent())
                if (lucky != null) {
                    val (picker, target) = lucky
                    Text(
                        text = "🎉 $picker → $target",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isOwnMessage) TextWhite else OnSurface
                    )
                    return@Column
                }
                val parsedBody = message.parsedContent()
                if (com.maodouchat.util.CaptureAlertPolicy.isCaptureAlert(parsedBody)) {
                    // 8.49 防御：解析失败直接跳过（此前 parse()!! 依赖「isCaptureAlert 与 parse 永远一致」的脆弱不变量）
                    val parsedAlert = com.maodouchat.util.CaptureAlertPolicy.parse(parsedBody) ?: return@Column
                    val (_, detail) = parsedAlert
                    Text(
                        text = "ALERT: $detail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage) TextWhite else Error,
                        fontWeight = FontWeight.SemiBold
                    )
                    return@Column
                }
                val poll = com.maodouchat.util.GroupPlayPolicy.parsePoll(parsedBody)
                if (poll != null) {
                    InteractivePollCard(
                        pollJson = poll,
                        isOwnMessage = isOwnMessage,
                        onVote = onPollVote
                    )
                    return@Column
                }
                val playLabel = run {
                    com.maodouchat.util.GroupPlayPolicy.parseDice(parsedBody)?.let { (sides, value) -> return@run "Dice $value / $sides" }
                    com.maodouchat.util.GroupPlayPolicy.parseRps(parsedBody)?.let { return@run "RPS: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLuckyDraw(parsedBody)?.let { (picker, target) -> return@run "Lucky: $picker -> $target" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.CHECKIN_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.CHECKIN_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.TRUTH_PREFIX)) { return@run "Truth: " + parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.TRUTH_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.ANON_PREFIX)) { return@run "Anon: " + parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.ANON_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseReactionRace(parsedBody)?.let { (token, label) -> return@run "Race $token: $label" }
                    com.maodouchat.util.GroupPlayPolicy.parseNumberBomb(parsedBody)?.let { (max, _, label) -> return@run "Bomb 1-$max: $label" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.WORD_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.WORD_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseWouldYouRather(parsedBody)?.let { (a, b, _) -> return@run "Would you rather: $a  OR  $b" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.EMOJI_RAIN_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.EMOJI_RAIN_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseTwoTruthsOneLie(parsedBody)?.let { items -> return@run "Two truths & one lie: " + items.joinToString(" / ") }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.QUIZ_PREFIX)) {
                        val q = parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.QUIZ_PREFIX).substringBefore('|')
                        return@run "Quiz: $q"
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseCharades(parsedBody)?.let { return@run "Charades: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseNumberGuess(parsedBody)?.let { (_, max) -> return@run "Number guess 1..$max" }
                    com.maodouchat.util.GroupPlayPolicy.parseRiddle(parsedBody)?.let { (q, _) -> return@run "Riddle: $q" }
                    com.maodouchat.util.GroupPlayPolicy.parseImpostor(parsedBody)?.let { return@run "Impostor game started" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSimon(parsedBody)?.let { return@run "Simon: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHotOrNot(parsedBody)?.let { return@run "Hot or not: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseAlphabet(parsedBody)?.let { return@run "Alphabet ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTrivia(parsedBody)?.let { (q, _) -> return@run "Trivia: $q" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpeedChallenge(parsedBody)?.let { return@run "Speed ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseFortune(parsedBody)?.let { return@run "Fortune: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiQuiz(parsedBody)?.let { (p, _) -> return@run "Emoji quiz: $p" }
                    com.maodouchat.util.GroupPlayPolicy.parseChainReact(parsedBody)?.let { return@run "Chain: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDebate(parsedBody)?.let { return@run "Debate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMirror(parsedBody)?.let { return@run "Mirror: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHideSeek(parsedBody)?.let { return@run "Hide&Seek: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseToast(parsedBody)?.let { return@run "Roast: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHotPotato(parsedBody)?.let { return@run "Hot potato ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseWordHint(parsedBody)?.let { (h, _) -> return@run "Word hint: $h" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpyfall(parsedBody)?.let { return@run "Spyfall @ ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseAcrostic(parsedBody)?.let { return@run "Acrostic: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiTranslate(parsedBody)?.let { (p, _) -> return@run "Emoji TR: $p" }
                    com.maodouchat.util.GroupPlayPolicy.parseTwentyQuestions(parsedBody)?.let { return@run "20Q: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRhyme(parsedBody)?.let { return@run "Rhyme: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseOddOneOut(parsedBody)?.let { (o, _) -> return@run "Odd one: $o" }
                    com.maodouchat.util.GroupPlayPolicy.parseCategories(parsedBody)?.let { return@run "Categories: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePasswordGame(parsedBody)?.let { return@run "Password game: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTimeCapsule(parsedBody)?.let { return@run "Capsule: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTaboo(parsedBody)?.let { return@run "Taboo: ${it.substringBefore('|')}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLightning(parsedBody)?.let { return@run "Lightning: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTwoWords(parsedBody)?.let { return@run "Two words: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWhisper(parsedBody)?.let { return@run "Whisper: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiDuel(parsedBody)?.let { return@run "Emoji duel: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCountdownRace(parsedBody)?.let { return@run "Race ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseRapidFire(parsedBody)?.let { return@run "Rapid: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiMemory(parsedBody)?.let { return@run "Memory: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseGeoGuess(parsedBody)?.let { return@run "Geo: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseOneWord(parsedBody)?.let { return@run "One word: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpeedMath(parsedBody)?.let { return@run "Math: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseStorySeed(parsedBody)?.let { return@run "Story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWould2(parsedBody)?.let { (a, b) -> return@run "Would you: $a OR $b" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiOnly(parsedBody)?.let { return@run "Emoji only: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseBlindDraw(parsedBody)?.let { return@run "Blind draw" }
                    com.maodouchat.util.GroupPlayPolicy.parseAlphabetRace(parsedBody)?.let { return@run "Alphabet: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSilentMovie(parsedBody)?.let { return@run "Silent movie: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseColorWord(parsedBody)?.let { return@run "Color word: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDebateFlash(parsedBody)?.let { return@run "Debate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseQuickPoll(parsedBody)?.let { return@run "Quick poll: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMirrorEcho(parsedBody)?.let { return@run "Mirror: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSyncClap(parsedBody)?.let { return@run "Sync clap x${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFactOrFiction(parsedBody)?.let { return@run "Fact?: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseImpulseDraw(parsedBody)?.let { return@run "Impulse: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWordScramble(parsedBody)?.let { return@run "Scramble: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseReactionDuel(parsedBody)?.let { return@run "React duel: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCodeBreaker(parsedBody)?.let { return@run "Code breaker" }
                    com.maodouchat.util.GroupPlayPolicy.parseSillyLaw(parsedBody)?.let { return@run "Law: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiMath(parsedBody)?.let { return@run "Emoji math: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePinTheMood(parsedBody)?.let { return@run "Mood pin: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRevokeRush(parsedBody)?.let { return@run "Revoke rush ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSecretSignal(parsedBody)?.let { return@run "Signal: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMoodMeter(parsedBody)?.let { return@run "Mood meter: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFocusSprint(parsedBody)?.let { return@run "Focus sprint ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseGratitudeRound(parsedBody)?.let { return@run "Gratitude: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseIdeaRelay(parsedBody)?.let { return@run "Idea relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTempoTap(parsedBody)?.let { return@run "Tempo tap ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTranslateRelay(parsedBody)?.let { return@run "Translate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseInviteRace(parsedBody)?.let { return@run "Invite race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMentionMayhem(parsedBody)?.let { return@run "Mention mayhem: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLinkHunt(parsedBody)?.let { return@run "Link hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseNudgeDash(parsedBody)?.let { return@run "Nudge dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCodeCheck(parsedBody)?.let { return@run "Code check: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTrustSprint(parsedBody)?.let { return@run "Trust sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseQrQuest(parsedBody)?.let { return@run "QR quest: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseContactSwap(parsedBody)?.let { return@run "Contact swap: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseScanSprint(parsedBody)?.let { return@run "Scan sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpoilerRace(parsedBody)?.let { return@run "Spoiler race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseBlurBattle(parsedBody)?.let { return@run "Blur battle: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDownloadDash(parsedBody)?.let { return@run "Download dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePinDrop(parsedBody)?.let { return@run "Pin drop: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFileRelay(parsedBody)?.let { return@run "File relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMapDash(parsedBody)?.let { return@run "Map dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseVaultLock(parsedBody)?.let { return@run "Vault lock: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWatermarkHunt(parsedBody)?.let { return@run "Watermark hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSecureSprint(parsedBody)?.let { return@run "Secure sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePhotoRace(parsedBody)?.let { return@run "Photo race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseClipDash(parsedBody)?.let { return@run "Clip dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFrameHunt(parsedBody)?.let { return@run "Frame hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSummaryCircle(parsedBody)?.let { return@run "Summary circle: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRewriteRelay(parsedBody)?.let { return@run "Rewrite relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePromptSprint(parsedBody)?.let { return@run "Prompt sprint: ${it}"
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseSuggestCircle(parsedBody)?.let { return@run "Suggest circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVoiceRace(parsedBody)?.let { return@run "Voice race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseReplySprint(parsedBody)?.let { return@run "Reply sprint: " + it
                    }
                    com.maodouchat.util.GroupPlayPolicy.parsePixelQuest(parsedBody)?.let { return@run "Pixel quest: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseAssistCircle(parsedBody)?.let { return@run "Assist circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseDecisionDash(parsedBody)?.let { return@run "Decision dash: " + it
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseDocHunt(parsedBody)?.let { return@run "Doc hunt: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMeaningRace(parsedBody)?.let { return@run "Meaning race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseInsightSprint(parsedBody)?.let { return@run "Insight sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseGifRelay(parsedBody)?.let { return@run "Gif relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMarkHunt(parsedBody)?.let { return@run "Mark hunt: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLeakSprint(parsedBody)?.let { return@run "Leak sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVoiceRing(parsedBody)?.let { return@run "Voice ring: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVideoStage(parsedBody)?.let { return@run "Video stage: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRingDash(parsedBody)?.let { return@run "Ring dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseWallPick(parsedBody)?.let { return@run "Wall pick: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFontRace(parsedBody)?.let { return@run "Font race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseThemeSprint(parsedBody)?.let { return@run "Theme sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseUnreadRush(parsedBody)?.let { return@run "Unread rush: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRingChoir(parsedBody)?.let { return@run "Ring choir: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseAlertSprint(parsedBody)?.let { return@run "Alert sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSoundWave(parsedBody)?.let { return@run "Sound wave: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePreviewMask(parsedBody)?.let { return@run "Preview mask: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseBeepDash(parsedBody)?.let { return@run "Beep dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePushRace(parsedBody)?.let { return@run "Push race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRemindCircle(parsedBody)?.let { return@run "Remind circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseWakeSprint(parsedBody)?.let { return@run "Wake sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseQuietHour(parsedBody)?.let { return@run "Quiet hour: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseOfflineHint(parsedBody)?.let { return@run "Offline hint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFallbackDash(parsedBody)?.let { return@run "Fallback dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseClickBeat(parsedBody)?.let { return@run "Click beat: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseBuzzRelay(parsedBody)?.let { return@run "Buzz relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFeelSprint(parsedBody)?.let { return@run "Feel sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSlideRace(parsedBody)?.let { return@run "Slide race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFadeCircle(parsedBody)?.let { return@run "Fade circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSpringDash(parsedBody)?.let { return@run "Spring dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSnapGuard(parsedBody)?.let { return@run "Snap guard: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRecentsHide(parsedBody)?.let { return@run "Recents hide: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseShieldSprint(parsedBody)?.let { return@run "Shield sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseCopyLock(parsedBody)?.let { return@run "Copy lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseExportSeal(parsedBody)?.let { return@run "Export seal: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLeakWall(parsedBody)?.let { return@run "Leak wall: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseForwardSeal(parsedBody)?.let { return@run "Forward seal: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseChatExportLock(parsedBody)?.let { return@run "Chat export lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVaultFence(parsedBody)?.let { return@run "Vault fence: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSealSprint(parsedBody)?.let { return@run "Seal sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePqxdhDash(parsedBody)?.let { return@run "PQXDH dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseCertRelay(parsedBody)?.let { return@run "Cert relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMarkSprint(parsedBody)?.let { return@run "Mark sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFadeTimer(parsedBody)?.let { return@run "Fade timer: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseStampRelay(parsedBody)?.let { return@run "Stamp relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLinkLock(parsedBody)?.let { return@run "Link lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePreviewMute(parsedBody)?.let { return@run "Preview mute: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseUrlFence(parsedBody)?.let { return@run "URL fence: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseNotifMask(parsedBody)?.let { return@run "Notif mask: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseListBlur(parsedBody)?.let { return@run "List blur: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseTraySeal(parsedBody)?.let { return@run "Tray seal: " + it }
                    null
                }
                if (playLabel != null) {
                    Text(
                        text = playLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage) TextWhite else OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    return@Column
                }
                val markdownAllowed = RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.MARKDOWN)
                val linkContext = LocalContext.current
                val useMarkdown = markdownAllowed && (
                    message.type == MessageType.MARKDOWN ||
                    parsedMeta.markdown ||
                    ChatMarkdown.looksLikeMarkdown(message.parsedContent())
                )
                if (useMarkdown) {
                    MarkdownMessageContent(
                        text = message.parsedContent(),
                        isOwnMessage = isOwnMessage,
                        onLinkClick = { url ->
                            // scheme 白名单：仅允许 http/https（防 javascript:/intent: 等危险 scheme）
                            val parsed = android.net.Uri.parse(url)
                            val scheme = parsed.scheme?.lowercase().orEmpty()
                            if (scheme != "http" && scheme != "https") {
                                android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_open_link_failed), android.widget.Toast.LENGTH_SHORT).show()
                                return@MarkdownMessageContent
                            }
                            // 密聊外链拦截：开关开启时阻断外链跳转（防社工/钓鱼）
                            if (!secretChatId.isNullOrBlank() && RuntimeFlags.isEnabled(linkContext, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)) {
                                android.widget.Toast.makeText(
                                    linkContext,
                                    linkContext.getString(com.maodouchat.R.string.secret_external_link_blocked),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@MarkdownMessageContent
                            }
                            runCatching {
                                linkContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_open_link_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    RichTextContent(
                        text = message.parsedContent().ifBlank { message.content },
                        mentionedUserIds = mentionedUserIds,
                        isOwnMessage = isOwnMessage,
                        onContactCardClick = onContactCardClick,
                        onLinkClick = { url ->
                            // 1.17：名片点击 → 打开该用户资料
                            if (url.startsWith("contactcard://")) {
                                val cardUserId = url.removePrefix("contactcard://")
                                if (onContactCardClick != null) {
                                    onContactCardClick(cardUserId)
                                } else {
                                    android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_contact_card_tap_hint), android.widget.Toast.LENGTH_SHORT).show()
                                }
                                return@RichTextContent
                            }
                            // scheme 白名单：仅允许 http/https（findUrlRanges 已限制前缀，双保险）
                            val parsed = android.net.Uri.parse(url)
                            val scheme = parsed.scheme?.lowercase().orEmpty()
                            if (scheme != "http" && scheme != "https") {
                                android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_open_link_failed), android.widget.Toast.LENGTH_SHORT).show()
                                return@RichTextContent
                            }
                            if (!secretChatId.isNullOrBlank() && RuntimeFlags.isEnabled(linkContext, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)) {
                                android.widget.Toast.makeText(
                                    linkContext,
                                    linkContext.getString(com.maodouchat.R.string.secret_external_link_blocked),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@RichTextContent
                            }
                            runCatching {
                                linkContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_open_link_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            LinkPreviewSlot(
                messageContent = message.content,
                isOwnMessage = isOwnMessage,
                secretChat = !secretChatId.isNullOrBlank(),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 280.dp)
            )

            if (isTranslating || !translationText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.58f) else palette.chatInputBackground)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = if (isOwnMessage) TextWhite else Primary)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = translationText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_translating),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) TextWhite else OnSurface
                    )
                }
            }

            if (!safetyWarning.isNullOrBlank()) {
                val dismissInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val dismissPressed by dismissInteractionSource.collectIsPressedAsState()
                val dismissScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (dismissPressed) 0.9f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 480f),
                    label = "safetyDismissScale"
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Error.copy(alpha = if (isOwnMessage) 0.18f else 0.10f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = safetyWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOwnMessage) TextWhite else OnSurface
                        )
                        if (onDismissSafety != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.chat_safety_dismiss),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = Primary,
                                modifier = Modifier
                                    .graphicsLayer { scaleX = dismissScale; scaleY = dismissScale }
                                    .clickable(
                                        interactionSource = dismissInteractionSource,
                                        indication = androidx.compose.material3.ripple(),
                                        onClick = onDismissSafety
                                    )
                            )
                        }
                    }
                }
            }

            // @ 提示（"@我"）
            if (mentionedUserIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(R.plurals.message_mentions_count, mentionedUserIds.size, mentionedUserIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary
                )
            }

            val kb = message.parsedMeta().inlineKeyboard
            if (kb.isNotEmpty()) {
                InlineKeyboardGrid(
                    rows = kb,
                    isOwnMessage = isOwnMessage,
                    messageId = message.id,
                    onClick = onInlineKeyboardClick
                )
            }

            // 时间 + 状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            ) {
                if (message.starred) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.chat_starred_status),
                        tint = if (isOwnMessage) TextWhiteSecondary else Primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                if (message.parsedMeta().silent) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = if (isOwnMessage) TextWhiteSecondary else TextHint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwnMessage) TextWhiteSecondary else TextHint
                )
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                if (message.editedAt != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.message_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwnMessage) TextWhiteSecondary else TextHint
                    )
                }
                if (isOwnMessage) {
                    Spacer(modifier = Modifier.width(4.dp))
                    if (onStatusClick != null) {
                        Box(modifier = Modifier.clickable { onStatusClick(message) }) {
                            MessageStatusIcon(message.status)
                        }
                    } else {
                        MessageStatusIcon(message.status)
                    }
                }
                if (onReply != null && message.type != MessageType.SYSTEM) {
                    Spacer(modifier = Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(onClick = { onReply(message) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.message_reply), style = MaterialTheme.typography.labelSmall, color = if (isOwnMessage) TextWhiteSecondary else TextHint)
                    }
                }
            }
            ReactionSummaryRow(
                message = message,
                currentUserId = currentUserId,
                isOwnMessage = isOwnMessage,
                onReactionClick = onReactionClick
            )
        }
    }
}

@Composable
private fun ImageBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String? = null,
    onImageClick: ((Message) -> Unit)? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    transferProgress: Float? = null,
    transferState: String? = null,
    transferError: String? = null,
    onPauseTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    onRequestAttachment: ((String) -> Unit)? = null,
    downloadFailed: Boolean = false,
    currentUserId: String? = null,
    onReactionClick: ((String) -> Unit)? = null,
    secretChatId: String? = null,
    onViewOnceOpened: ((String) -> Unit)? = null,
    onRevealSpoiler: ((String) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val viewOnce = com.maodouchat.util.ViewOncePolicy.isViewOnce(message)
    val viewOnceLocked = com.maodouchat.util.ViewOncePolicy.isLockedForViewer(message, isOwnMessage)
    val spoilerMeta = message.parsedMeta()
    val spoilerHidden = spoilerMeta.spoilerMedia && !spoilerMeta.spoilerRevealed && !isOwnMessage
    val mediaContent = message.parsedContent()
    val needsDownload = remember(message.id, message.content) {
        !viewOnceLocked && message.parsedMeta().attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    LaunchedEffect(message.id, message.content, needsDownload) {
        if (needsDownload) onRequestAttachment?.invoke(message.id)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            if (showAvatar) {
                Avatar(
                    name = senderName ?: "?",
                    size = AvatarSize.SM,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        }

        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .widthIn(max = 260.dp)
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            ReceivedBubbleShape
                        ) else Modifier
                    )
                    .padding(4.dp)
                    .then(
                        if (viewOnceLocked) Modifier
                        else if (spoilerHidden) Modifier.clickable {
                            onRevealSpoiler?.invoke(message.id)
                        }
                        else if (onImageClick != null && !needsDownload && transferState == null) Modifier.clickable {
                            onImageClick(message)
                            if (viewOnce && !isOwnMessage) onViewOnceOpened?.invoke(message.id)
                        } else Modifier
                    )
            ) {
                if (viewOnceLocked) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.view_once_viewed),
                            color = TextWhite,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                val secretPayload = remember(secretChatId, currentUserId) {
                    if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
                    else {
                        val dh = android.provider.Settings.Secure.getString(
                            context.contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID
                        )
                        com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
                    }
                }
                AsyncImage(
                    model = OwnerScopedImageKeys.request(
                        context = LocalContext.current,
                        data = mediaContent.takeUnless { needsDownload },
                        sizeWidth = 1024,
                        sizeHeight = 1024,
                        secretPayload = secretPayload,
                    ),
                    contentDescription = stringResource(R.string.message_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(220.dp)
                        .height(220.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                if (spoilerHidden && !viewOnceLocked) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.spoiler_tap_to_reveal),
                            color = TextWhite,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                } // end not viewOnceLocked

                if (transferState != null) {
                    AttachmentTransferOverlay(
                        messageId = message.id,
                        state = transferState,
                        errorCode = transferError,
                        progress = transferProgress,
                        onPause = onPauseTransfer,
                        onResume = onResumeTransfer,
                        onCancel = onCancelTransfer,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (needsDownload && downloadFailed) {
                    IconButton(
                        onClick = { onRequestAttachment?.invoke(message.id) },
                        modifier = Modifier.align(Alignment.Center).size(48.dp)
                            .background(Color.Black.copy(alpha = 0.56f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.chat_retry), tint = Color.White)
                    }
                } else if (needsDownload) {
                    CircularProgressIndicator(
                        progress = { (transferProgress ?: 0f).coerceIn(0f, 1f) },
                        color = Color.White,
                        trackColor = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.Center).size(42.dp),
                        strokeWidth = 3.dp
                    )
                }

                // 右下角时间 + 阅后即焚倒计时
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            Color.Black.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = true)
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}

@Composable
private fun VoiceBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    voiceTranscript: String? = null,
    isTranscribing: Boolean = false,
    onRequestVoiceTranscript: ((String) -> Unit)? = null,
    onCopyVoiceTranscript: ((String) -> Unit)? = null,
    transferProgress: Float? = null,
    transferState: String? = null,
    transferError: String? = null,
    onPauseTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    onRequestAttachment: ((String) -> Unit)? = null,
    downloadFailed: Boolean = false,
    currentUserId: String? = null,
    onReactionClick: ((String) -> Unit)? = null,
    /** 1.70：点击已读状态图标打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val mediaContent = message.parsedContent()
    val needsDownload = remember(message.id, message.content) {
        message.parsedMeta().attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    LaunchedEffect(message.id, message.content, needsDownload) {
        if (needsDownload) onRequestAttachment?.invoke(message.id)
    }
    val playerState by com.maodouchat.util.VoicePlayer.state.collectAsState()
    val isThisPlaying = playerState.messageId == message.id && playerState.isPlaying
    val isThisActive = playerState.messageId == message.id
    // 1.176：该语音是否已播放（气泡未读红点）
    val isVoicePlayed = remember(message.id) { com.maodouchat.util.VoicePlayedStore.isPlayed(context, message.id) }
    val progress = if (isThisActive) playerState.progress else 0f
    val knownDuration = playerState.takeIf { it.messageId == message.id && it.durationMs > 0L }?.durationMs
        ?: message.parsedMeta().voiceDurationMs
        ?: 1_000L
    val totalSeconds = knownDuration / 1000
    val displaySeconds = if (totalSeconds > 0) totalSeconds else 1 // 无 duration 时默认 1s
    val displayText = "%d:%02d".format(displaySeconds / 60, displaySeconds % 60)
    val speedLabel = com.maodouchat.util.VoicePlayer.formatSpeedLabel(
        if (isThisActive) playerState.speed else 1f
    )
    val earpiece = if (isThisActive) playerState.earpiece else false

    // 注意：不再在气泡 onDispose 时停播——LazyColumn 回收滚出视口的气泡会触发 onDispose，
    // 导致滚动阅读历史时正在播放的语音被骤然打断。播放归属聊天屏生命周期：
    // ChatDetailViewModel.onCleared() 统一 VoicePlayer.stop()（返回/退出聊天即停）。
    // 自然播完的连播逻辑走 VoicePlayer 的 onCompletion（见 ChatDetailScreen）。

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            if (showAvatar) {
                Avatar(
                    name = senderName ?: "?",
                    size = AvatarSize.SM,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        }

        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .width(240.dp)
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
                    .background(if (isOwnMessage) Brush.linearGradient(com.maodouchat.ui.theme.ChatBubbleColorPalette.gradient(LocalChatBubbleColor.current)) else Brush.linearGradient(listOf(palette.chatBubbleReceived, palette.chatBubbleReceived)))
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            ReceivedBubbleShape
                        ) else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.then(
                            if (!needsDownload && transferState == null) {
                                Modifier.clickable {
                                    com.maodouchat.util.VoicePlayer.ensureContext(context)
                                    if (playerState.messageId == message.id) {
                                        com.maodouchat.util.VoicePlayer.togglePlayPause(
                                            message.id,
                                            mediaContent,
                                            context,
                                        )
                                    } else {
                                        com.maodouchat.util.VoicePlayer.play(
                                            message.id,
                                            mediaContent,
                                            context,
                                        )
                                    }
                                }
                            } else Modifier
                        )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (isOwnMessage) Color.White else Primary,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isThisPlaying) stringResource(R.string.message_pause) else stringResource(R.string.message_play),
                                tint = if (isOwnMessage) Primary else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // 模拟波形 + 实时进度覆盖
                        Box(modifier = Modifier.weight(1f).height(28.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.CenterStart)
                            ) {
                                WaveHeights.forEach { h ->
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height((24 * h).dp)
                                            .background(
                                                if (isOwnMessage) Color.White.copy(alpha = 0.5f)
                                                else Primary.copy(alpha = 0.3f),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                            val activeCount = (WaveHeights.size * progress).toInt().coerceIn(0, WaveHeights.size)
                            if (activeCount > 0) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) {
                                    WaveHeights.take(activeCount).forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height((24 * h).dp)
                                                .background(
                                                    if (isOwnMessage) Color.White else Primary,
                                                    RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 8.44：播放进度条（仅当前播放中显示，可拖动跳转）
                    if (isThisActive && knownDuration > 0L) {
                        Slider(
                            value = progress.coerceIn(0f, 1f),
                            onValueChange = {
                                com.maodouchat.util.VoicePlayer.seekTo(
                                    message.id,
                                    (it * knownDuration).toLong()
                                )
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = if (isOwnMessage) Color.White else Primary,
                                activeTrackColor = if (isOwnMessage) Color.White else Primary,
                                inactiveTrackColor = if (isOwnMessage) Color.White.copy(alpha = 0.3f) else Primary.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp)
                                .height(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isOwnMessage) TextWhite else OnSurface
                        )
                        Text(
                            text = formatTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwnMessage) TextWhiteSecondary else TextHint
                        )
                        DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                        // 1.176：未播语音红点（他人消息、未播放且未在播放中）
                        if (!isOwnMessage && !isVoicePlayed && !isThisPlaying) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(UnreadRed))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (!needsDownload && transferState == null) {
                            Text(
                                text = speedLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isOwnMessage) TextWhite else Primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        com.maodouchat.util.VoicePlayer.ensureContext(context)
                                        if (!isThisActive) {
                                            com.maodouchat.util.VoicePlayer.play(
                                                message.id,
                                                mediaContent,
                                                context,
                                            )
                                        }
                                        com.maodouchat.util.VoicePlayer.cycleSpeed()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Text(
                                text = if (earpiece) "T" else "S",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isOwnMessage) TextWhite else Primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        onClickLabel = stringResource(
                                            if (earpiece) R.string.message_voice_speaker
                                            else R.string.message_voice_earpiece
                                        )
                                    ) {
                                        com.maodouchat.util.VoicePlayer.ensureContext(context)
                                        com.maodouchat.util.VoicePlayer.toggleEarpiece(context)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (isOwnMessage) {
                            if (onStatusClick != null) {
                                Box(modifier = Modifier.clickable { onStatusClick(message) }) {
                                    MessageStatusIcon(message.status)
                                }
                            } else {
                                MessageStatusIcon(message.status)
                            }
                        }
                    }
                }
                if (transferState != null) {
                    AttachmentTransferOverlay(
                        messageId = message.id,
                        state = transferState,
                        errorCode = transferError,
                        progress = transferProgress,
                        onPause = onPauseTransfer,
                        onResume = onResumeTransfer,
                        onCancel = onCancelTransfer,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (needsDownload && downloadFailed) {
                    IconButton(
                        onClick = { onRequestAttachment?.invoke(message.id) },
                        modifier = Modifier.align(Alignment.Center).size(42.dp)
                            .background(Color.Black.copy(alpha = 0.34f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.chat_retry), tint = Color.White)
                    }
                } else if (needsDownload) {
                    CircularProgressIndicator(
                        progress = { (transferProgress ?: 0f).coerceIn(0f, 1f) },
                        color = if (isOwnMessage) Color.White else Primary,
                        trackColor = if (isOwnMessage) Color.White.copy(alpha = 0.25f) else Primary.copy(alpha = 0.18f),
                        modifier = Modifier.align(Alignment.Center).size(34.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
            val showInlineEntry = com.maodouchat.util.VoiceTranscriptPolicy.shouldShowInlineEntry(
                isVoiceMessage = true,
                transcript = voiceTranscript,
                isTranscribing = isTranscribing
            )
            if (showInlineEntry && onRequestVoiceTranscript != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isOwnMessage) Color.White.copy(alpha = 0.18f) else palette.chatInputBackground)
                        .clickable { onRequestVoiceTranscript.invoke(message.id) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = stringResource(R.string.chat_transcribe),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isOwnMessage) TextWhite else Primary
                    )
                }
            } else if (isTranscribing || com.maodouchat.util.VoiceTranscriptPolicy.hasTranscript(voiceTranscript)) {
                var expanded by remember(message.id) { mutableStateOf(false) }
                val needsToggle = com.maodouchat.util.VoiceTranscriptPolicy.needsExpandToggle(voiceTranscript)
                val body = if (isTranscribing) {
                    stringResource(R.string.message_transcribing)
                } else {
                    com.maodouchat.util.VoiceTranscriptPolicy.displayText(voiceTranscript, expanded)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.58f) else palette.chatInputBackground)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (isTranscribing) {
                            val pulseAlpha by rememberMotionPulse(
                                initialValue = 0.45f,
                                targetValue = 1f,
                                durationMillis = 800,
                                label = "voicePulse"
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp).graphicsLayer { alpha = pulseAlpha },
                                strokeWidth = 2.dp,
                                color = if (isOwnMessage) TextWhite else Primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOwnMessage) TextWhite else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!isTranscribing && com.maodouchat.util.VoiceTranscriptPolicy.hasTranscript(voiceTranscript)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (needsToggle) {
                                Text(
                                    text = stringResource(
                                        if (expanded) R.string.chat_transcript_collapse
                                        else R.string.chat_transcript_expand
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOwnMessage) TextWhite else Primary,
                                    modifier = Modifier.clickable { expanded = !expanded }
                                )
                            }
                            if (onCopyVoiceTranscript != null) {
                                Text(
                                    text = stringResource(R.string.chat_copy_transcript),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOwnMessage) TextWhite else Primary,
                                    modifier = Modifier.clickable {
                                        onCopyVoiceTranscript.invoke(
                                            com.maodouchat.util.VoiceTranscriptPolicy.normalize(voiceTranscript)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}

@Composable
private fun VideoBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String? = null,
    onVideoClick: ((Message) -> Unit)? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    transferProgress: Float? = null,
    transferState: String? = null,
    transferError: String? = null,
    onPauseTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    onRequestAttachment: ((String) -> Unit)? = null,
    downloadFailed: Boolean = false,
    currentUserId: String? = null,
    onReactionClick: ((String) -> Unit)? = null,
    secretChatId: String? = null
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val mediaContent = message.parsedContent()
    val needsDownload = remember(message.id, message.content) {
        message.parsedMeta().attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    LaunchedEffect(message.id, message.content, needsDownload) {
        if (needsDownload) onRequestAttachment?.invoke(message.id)
    }
    val secretPayload = remember(secretChatId, currentUserId) {
        if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
        else {
            val dh = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            if (showAvatar) {
                Avatar(name = senderName ?: "?", size = AvatarSize.SM, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        }

        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .widthIn(max = 260.dp)
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(if (!isOwnMessage) Modifier.border(1.dp, palette.chatBubbleReceivedBorder, ReceivedBubbleShape) else Modifier)
                    .padding(4.dp)
                    .then(if (onVideoClick != null && !needsDownload && transferState == null) Modifier.clickable { onVideoClick(message) } else Modifier)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 视频缩略图（用 AsyncImage 加载）
                    AsyncImage(
                        model = OwnerScopedImageKeys.request(
                            context = LocalContext.current,
                            data = mediaContent.takeUnless { needsDownload },
                            sizeWidth = 640,
                            sizeHeight = 360,
                            secretPayload = secretPayload,
                        ),
                        contentDescription = stringResource(R.string.message_video),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).height(160.dp)
                    )
                    // 播放按钮叠加
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.message_play_video),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    if (transferState != null) {
                        AttachmentTransferOverlay(
                            messageId = message.id,
                            state = transferState,
                            errorCode = transferError,
                            progress = transferProgress,
                            onPause = onPauseTransfer,
                            onResume = onResumeTransfer,
                            onCancel = onCancelTransfer
                        )
                    } else if (needsDownload && downloadFailed) {
                        IconButton(
                            onClick = { onRequestAttachment?.invoke(message.id) },
                            modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.56f), CircleShape)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.chat_retry), tint = Color.White)
                        }
                    } else if (needsDownload) {
                        CircularProgressIndicator(
                            progress = { (transferProgress ?: 0f).coerceIn(0f, 1f) },
                            color = Color.White,
                            trackColor = Color.Black.copy(alpha = 0.35f),
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
                // 右下角时间 + 阅后即焚倒计时
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = true)
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}

@Composable
private fun FileBubble(
    message: Message,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    onFileClick: ((Message) -> Unit)? = null,
    transferProgress: Float? = null,
    transferState: String? = null,
    transferError: String? = null,
    onPauseTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    currentUserId: String? = null,
    onReactionClick: ((String) -> Unit)? = null,
    /** 1.70：点击已读状态图标打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    val defaultFileName = stringResource(R.string.message_file)
    val metadata = remember(message.content) { message.parsedMeta() }
    val fileName = remember(message.content, metadata.fileName) {
        metadata.fileName?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(message.parsedContent()).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: defaultFileName }.getOrDefault(defaultFileName)
            .ifBlank { defaultFileName }
    }
    val fileDetails = remember(metadata.fileMimeType, metadata.fileSizeBytes, message.timestamp) {
        val mime = metadata.fileMimeType?.substringAfterLast('/')?.uppercase()?.takeIf { it.isNotBlank() && it != "OCTET-STREAM" }
        val size = metadata.fileSizeBytes?.takeIf { it > 0 }?.let(::formatFileSize)
        listOfNotNull(mime, size, formatTime(message.timestamp)).joinToString(" · ")
    }
    val transferStatus = transferState?.let { state -> fileTransferStatusText(state, transferError) }
    val controlTint = if (isOwnMessage) Color.White else Primary
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwnMessage) {
            if (showAvatar) {
                Avatar(name = senderName ?: "?", size = AvatarSize.SM, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        }

        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .widthIn(max = 260.dp)
                    .clip(if (isOwnMessage) SentBubbleShape else ReceivedBubbleShape)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(if (!isOwnMessage) Modifier.border(1.dp, palette.chatBubbleReceivedBorder, ReceivedBubbleShape) else Modifier)
                    .then(if (onFileClick != null && transferState == null) Modifier.clickable { onFileClick(message) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp).background(if (isOwnMessage) Color.White else Primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = stringResource(R.string.message_file), tint = Primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fileName, style = MaterialTheme.typography.bodyMedium, color = if (isOwnMessage) TextWhite else OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fileDetails, style = MaterialTheme.typography.labelSmall, color = if (isOwnMessage) TextWhiteSecondary else TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        transferStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (transferState == AttachmentTransferState.FAILED) {
                                    if (isOwnMessage) Color.White else UnreadRed
                                } else if (isOwnMessage) TextWhiteSecondary else TextHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        transferProgress?.let { progress ->
                            Spacer(modifier = Modifier.height(5.dp))
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = if (isOwnMessage) Color.White else Primary,
                                trackColor = if (isOwnMessage) Color.White.copy(alpha = 0.25f) else Primary.copy(alpha = 0.14f)
                            )
                        }
                        when (transferState) {
                            AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING -> {
                                Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onPauseTransfer?.invoke(message.id) }, modifier = Modifier.size(32.dp), enabled = onPauseTransfer != null) {
                                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.chat_file_transfer_pause), tint = controlTint, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onCancelTransfer?.invoke(message.id) }, modifier = Modifier.size(32.dp), enabled = onCancelTransfer != null) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_file_transfer_cancel), tint = controlTint, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED -> {
                                Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onResumeTransfer?.invoke(message.id) }, modifier = Modifier.size(32.dp), enabled = onResumeTransfer != null) {
                                        Icon(
                                            if (transferState == AttachmentTransferState.FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                            contentDescription = stringResource(if (transferState == AttachmentTransferState.FAILED) R.string.chat_file_transfer_retry else R.string.chat_file_transfer_resume),
                                            tint = controlTint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onCancelTransfer?.invoke(message.id) }, modifier = Modifier.size(32.dp), enabled = onCancelTransfer != null) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_file_transfer_cancel), tint = controlTint, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                        if (isOwnMessage) {
                            Spacer(modifier = Modifier.width(6.dp))
                            if (onStatusClick != null) {
                                Box(modifier = Modifier.clickable { onStatusClick(message) }) {
                                    MessageStatusIcon(message.status)
                                }
                            } else {
                                MessageStatusIcon(message.status)
                            }
                        }
                    }
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}

@Composable
private fun AttachmentTransferOverlay(
    messageId: String,
    state: String,
    errorCode: String?,
    progress: Float?,
    onPause: ((String) -> Unit)?,
    onResume: ((String) -> Unit)?,
    onCancel: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 132.dp, max = 176.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = fileTransferStatusText(state, errorCode),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { (progress ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.24f)
        )
        Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING -> {
                    IconButton(onClick = { onPause?.invoke(messageId) }, enabled = onPause != null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Pause, stringResource(R.string.chat_file_transfer_pause), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED -> {
                    IconButton(onClick = { onResume?.invoke(messageId) }, enabled = onResume != null, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (state == AttachmentTransferState.FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            stringResource(if (state == AttachmentTransferState.FAILED) R.string.chat_file_transfer_retry else R.string.chat_file_transfer_resume),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            if (state in setOf(
                    AttachmentTransferState.PREPARING,
                    AttachmentTransferState.QUEUED,
                    AttachmentTransferState.UPLOADING,
                    AttachmentTransferState.PAUSED,
                    AttachmentTransferState.FAILED
                )
            ) {
                IconButton(onClick = { onCancel?.invoke(messageId) }, enabled = onCancel != null, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.chat_file_transfer_cancel), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun fileTransferStatusText(state: String, errorCode: String?): String = when (state) {
    AttachmentTransferState.PREPARING -> stringResource(R.string.chat_file_transfer_preparing)
    AttachmentTransferState.QUEUED -> stringResource(R.string.chat_file_transfer_queued)
    AttachmentTransferState.UPLOADING -> stringResource(R.string.chat_file_transfer_uploading)
    AttachmentTransferState.READY -> stringResource(R.string.chat_file_transfer_ready)
    AttachmentTransferState.SENDING -> stringResource(R.string.chat_file_transfer_sending)
    AttachmentTransferState.PAUSED -> stringResource(R.string.chat_file_transfer_paused)
    AttachmentTransferState.FAILED -> when {
        errorCode == "SOURCE_MISSING" -> stringResource(R.string.chat_file_transfer_source_missing)
        errorCode?.startsWith("NETWORK_") == true -> stringResource(R.string.chat_file_transfer_network_failed)
        errorCode?.startsWith("TIMEOUT_") == true -> stringResource(R.string.chat_file_transfer_timeout)
        errorCode?.startsWith("SEND_") == true -> stringResource(R.string.chat_file_transfer_send_failed)
        else -> stringResource(R.string.chat_file_transfer_failed)
    }
    else -> stringResource(R.string.chat_file_transfer_queued)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun SystemMessageBubble(content: String, modifier: Modifier = Modifier) {
    val palette = LocalChatPalette.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.systemMessageText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(palette.systemMessageBackground, SystemBubbleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    // 状态切换时做轻量 spring，解释发送中→已送达→已读；系统关闭动画时瞬时到位
    val motion = LocalMotionSettings.current
    val targetScale = when (status) {
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
        active = status == MessageStatus.SENDING,
        staticValue = 0.85f
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (status == MessageStatus.SENDING) pulse else 1f
            },
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            MessageStatus.SENDING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = TextWhiteSecondary
                )
            }
            MessageStatus.SENT -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.message_status_sent),
                    tint = TextWhiteSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            MessageStatus.DELIVERED -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.message_status_delivered),
                    tint = TextWhiteSecondary,
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
                    tint = UnreadRed,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

/** 阅后即焚剩余时间文案；无期限或已过期返回 null。 */
@Composable
private fun formatDisappearCountdown(expiresAt: Long?, nowMs: Long): String? {
    val remaining = com.maodouchat.util.DisappearingMessagePolicy.remainingMs(expiresAt, nowMs)
    if (remaining < 0L) return null
    val totalSec = (remaining / 1000L).coerceAtLeast(0L)
    return when {
        totalSec < 60L -> stringResource(R.string.disappear_countdown_s, totalSec.toInt())
        totalSec < 3600L -> {
            val m = (totalSec / 60L).toInt()
            val s = (totalSec % 60L).toInt()
            stringResource(R.string.disappear_countdown_m, m, s)
        }
        else -> {
            val h = (totalSec / 3600L).toInt()
            val m = ((totalSec % 3600L) / 60L).toInt()
            stringResource(R.string.disappear_countdown_h, h, m)
        }
    }
}

@Composable
private fun DisappearCountdownLabel(
    expiresAt: Long?,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier
) {
    if (expiresAt == null || expiresAt <= 0L) return
    var nowMs by remember(expiresAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            nowMs = System.currentTimeMillis()
            val remaining = com.maodouchat.util.DisappearingMessagePolicy.remainingMs(expiresAt, nowMs)
            if (remaining <= 0L) break
            // 1 小时内每秒刷新；更长时每 15 秒刷新以省电
            val delayMs = if (remaining > 3_600_000L) 15_000L else 1_000L
            kotlinx.coroutines.delay(delayMs)
        }
    }
    val label = formatDisappearCountdown(expiresAt, nowMs) ?: return
    Spacer(modifier = Modifier.width(4.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (isOwnMessage) TextWhiteSecondary else Primary,
        modifier = modifier
    )
}

/**
 * 本机链接预览：开关关/无 URL/失败均静默不渲染。
 * 仅在消息正文含 http(s) URL 时拉取；Repository 进程缓存命中时无 loading 闪烁。
 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun LinkPreviewSlot(
    messageContent: String,
    isOwnMessage: Boolean,
    secretChat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userEnabled = remember(LinkPreviewPreferences.version) { LinkPreviewPreferences.isEnabled(context) }
    val secretBlocksPreview = secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_LINK_PREVIEW_BLOCK)
    val enabled = userEnabled && !secretBlocksPreview
    val url = remember(messageContent, enabled) {
        if (!enabled) null else LinkPreviewPolicy.firstHttpUrl(messageContent)
    }
    if (url == null) return

    var preview by remember(url) {
        mutableStateOf(LinkPreviewRepository.cached(url))
    }

    LaunchedEffect(url) {
        // fetch 自带正/负缓存与 in-flight 去重；失败返回 null
        preview = LinkPreviewRepository.fetch(url)
    }

    val card = preview ?: return
    if (!LinkPreviewPolicy.isUseful(card)) return

    LinkPreviewCard(
        preview = card,
        isOwnMessage = isOwnMessage,
        modifier = modifier,
        onOpen = {
            if (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.maodouchat.R.string.secret_external_link_blocked),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@LinkPreviewCard
            }
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(card.url)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    )
}

@Composable
private fun LinkPreviewCard(
    preview: LinkPreviewPolicy.Preview,
    isOwnMessage: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalChatPalette.current
    val bg = if (isOwnMessage) {
        LocalChatBubbleColor.current.copy(alpha = 0.55f)
    } else {
        palette.chatInputBackground
    }
    val titleColor = if (isOwnMessage) TextWhite else OnSurface
    val descColor = if (isOwnMessage) TextWhiteSecondary else TextSecondary
    val hostColor = if (isOwnMessage) TextWhiteSecondary else TextHint
    val site = preview.siteName?.takeIf { it.isNotBlank() }
        ?: LinkPreviewPolicy.displayHost(preview.url)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onOpen)
            .padding(bottom = 8.dp)
    ) {
        if (!preview.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = LocalContext.current,
                    data = preview.imageUrl,
                    sizeWidth = 640,
                    sizeHeight = 360,
                ),
                contentDescription = stringResource(R.string.message_link_preview_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = site,
                style = MaterialTheme.typography.labelSmall,
                color = hostColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!preview.title.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!preview.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = descColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 富文本消息：高亮正文中的 @token（displayName 或遗留 userId）。 */
/** 1.17：从名片标记中提取目标用户 id（供点击打开资料）。 */
private val CONTACT_CARD_USER_RE = Regex("\\[contactUser:([^\\]]+)")

@Composable
private fun RichTextContent(
    text: String,
    mentionedUserIds: List<String>,
    isOwnMessage: Boolean,
    onContactCardClick: ((String) -> Unit)? = null,
    onLinkClick: (String) -> Unit = {}
) {
    // 1.11：先剥离名片标记，接收端不会看到裸 [contactUser:...]（1.18 复用 ChatMarkdown 统一实现）
    val cleanText = com.maodouchat.ui.component.ChatMarkdown.stripContactCardMarker(text)
    // 1.17：名片消息整体渲染为可点击链接（点击打开该用户资料）
    val cardUserId = remember(text) { CONTACT_CARD_USER_RE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } }
    if (cardUserId != null) {
        val cardUrl = "contactcard://$cardUserId"
        val annotatedCard = androidx.compose.ui.text.buildAnnotatedString {
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Clickable(
                    tag = cardUrl,
                    linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(cardUrl) }
                )
            ) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = if (isOwnMessage) TextWhite else androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                ) {
                    append(cleanText.ifBlank { text })
                }
            }
        }
        Text(
            text = annotatedCard,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = if (isOwnMessage) TextWhite else OnSurface
        )
        return
    }
    val mentionColor = androidx.compose.ui.graphics.Color(0xFFFFC107)
    val hasAt = cleanText.contains('@')
    val urlRanges = remember(cleanText) { findUrlRanges(cleanText) }
    if (!hasAt && mentionedUserIds.isEmpty() && urlRanges.isEmpty()) {
        Text(
            text = cleanText,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = if (isOwnMessage) TextWhite else OnSurface
        )
        return
    }
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < cleanText.length) {
            // 优先匹配 URL（避免 @ 把 URL 内片段误判为 mention）
            val urlHit = urlRanges.firstOrNull { it.first == i }
            if (urlHit != null) {
                val (start, end) = urlHit
                val url = cleanText.substring(start, end)
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = url,
                        linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(url) }
                    )
                ) {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = mentionColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(url)
                    }
                }
                i = end
                continue
            }
            if (cleanText[i] != '@' || (i > 0 && !cleanText[i - 1].isWhitespace())) {
                append(cleanText[i])
                i++
                continue
            }
            // 从 @ 扫到空白/标点
            var j = i + 1
            while (j < cleanText.length) {
                val ch = cleanText[j]
                if (ch.isWhitespace() || ch == ',' || ch == '.' || ch == '!' || ch == '?' ||
                    ch == '，' || ch == '。' || ch == '！' || ch == '？'
                ) break
                j++
            }
            if (j > i + 1) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = mentionColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                ) {
                    append(cleanText.substring(i, j))
                }
                i = j
            } else {
                append('@')
                i++
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
        color = if (isOwnMessage) TextWhite else OnSurface
    )
}

/** 扫描文本中的 http/https URL 起止区间（左闭右开），供 [RichTextContent] 渲染可点击链接。 */
private fun findUrlRanges(text: String): List<Pair<Int, Int>> {
    val ranges = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < text.length) {
        val start = if (text.startsWith("http://", i) || text.startsWith("https://", i)) i else -1
        if (start < 0) { i++; continue }
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in setOf('<', '>', '"', '\'')) {
            end++
        }
        while (end > start && text[end - 1] in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) {
            end--
        }
        if (end > start) ranges += start to end
        i = end.coerceAtLeast(start + 1)
    }
    return ranges
}

/** 输入框上方的「回复某条消息」提示条 */
@Composable
fun ReplyTargetBar(senderName: String, preview: String, onCancel: () -> Unit) {
    val palette = LocalChatPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chatInputBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.message_reply_to, senderName), style = MaterialTheme.typography.labelMedium, color = Primary)
            Text(preview, style = MaterialTheme.typography.bodySmall, color = TextHint, maxLines = 1)
        }
        androidx.compose.material3.TextButton(onClick = onCancel) {
            Text(stringResource(R.string.common_cancel), color = TextHint)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubblePreview() {
    MaterialTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            SystemMessageBubble("You nudged Alex Chen")
            MessageBubble(
                message = Message(
                    id = "1",
                    chatId = "c1",
                    senderId = "other",
                    content = "Hey, is the design review still on?",
                    timestamp = System.currentTimeMillis()
                ),
                isOwnMessage = false,
                showAvatar = true,
                senderName = "Alex Chen"
            )
            MessageBubble(
                message = Message(
                    id = "2",
                    chatId = "c1",
                    senderId = "me",
                    content = "Yes. I have the new component states ready.",
                    status = MessageStatus.READ,
                    timestamp = System.currentTimeMillis()
                ),
                isOwnMessage = true
            )
        }
    }
}

@Composable
private fun InteractivePollCard(
    pollJson: org.json.JSONObject,
    isOwnMessage: Boolean,
    onVote: ((String, Int) -> Unit)?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pollId = pollJson.optString("id")
    val question = pollJson.optString("q")
    val opts = pollJson.optJSONArray("options")
    val optionList = remember(pollJson.toString()) {
        buildList {
            if (opts != null) {
                for (i in 0 until opts.length()) add(opts.optString(i))
            }
        }
    }
    var counts by remember(pollId) { mutableStateOf(List(optionList.size) { 0 }) }
    var myVotes by remember(pollId) { mutableStateOf(emptySet<Int>()) }
    var totalVoters by remember(pollId) { mutableStateOf(0) }
    var closed by remember(pollId) { mutableStateOf(false) }
    var loading by remember(pollId) { mutableStateOf(false) }
    var voting by remember(pollId) { mutableStateOf(false) }

    fun applyServerJson(raw: String) {
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        val cArr = o.optJSONArray("counts")
        if (cArr != null) {
            counts = List(optionList.size) { idx -> if (idx < cArr.length()) cArr.optInt(idx) else 0 }
        }
        val my = o.optJSONArray("myVotes")
        myVotes = buildSet {
            if (my != null) for (i in 0 until my.length()) add(my.optInt(i))
        }
        totalVoters = o.optInt("totalVoters", totalVoters)
        closed = o.optBoolean("closed", closed)
    }

    LaunchedEffect(pollId) {
        if (pollId.isBlank()) return@LaunchedEffect
        loading = true
        val token = TokenManager.getInstance(context).getToken().orEmpty()
        if (token.isNotBlank()) {
            val result = withContext(Dispatchers.IO) { ApiService.getGroupPoll(token, pollId) }
            result.onSuccess { applyServerJson(it) }
        }
        loading = false
    }

    val titleColor = if (isOwnMessage) TextWhite else OnSurface
    val subColor = if (isOwnMessage) TextWhiteSecondary else TextHint
    val chipBg = if (isOwnMessage) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f)
    val selectedBg = if (isOwnMessage) Color.White.copy(alpha = 0.28f) else Primary.copy(alpha = 0.16f)
    val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "📊 $question",
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            fontWeight = FontWeight.SemiBold
        )
        optionList.forEachIndexed { index, label ->
            val count = counts.getOrElse(index) { 0 }
            val selected = index in myVotes
            val fraction = count.toFloat() / maxCount.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) selectedBg else chipBg)
                    .then(
                        if (onVote != null && pollId.isNotBlank() && !closed && !voting) {
                            Modifier.clickable {
                                if (voting || closed) return@clickable
                                voting = true
                                // Single path: ViewModel.votePoll -> API. Card only refreshes tallies.
                                onVote(pollId, index)
                                scope.launch {
                                    kotlinx.coroutines.delay(350)
                                    val token = TokenManager.getInstance(context).getToken().orEmpty()
                                    if (token.isNotBlank()) {
                                        val result = withContext(Dispatchers.IO) {
                                            ApiService.getGroupPoll(token, pollId)
                                        }
                                        result.onSuccess { applyServerJson(it) }
                                    }
                                    voting = false
                                }
                            }
                        } else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor
                    )
                    // tally bar
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.Black.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(if (isOwnMessage) Color.White.copy(alpha = 0.7f) else Primary)
                        )
                    }
                }
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = subColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        val status = buildString {
            if (closed) append("closed · ")
            append(stringResource(R.string.group_play_vote_hint))
            if (totalVoters > 0) append(" · ").append(totalVoters)
            if (loading) append(" · ...")
        }
        Text(text = status, style = MaterialTheme.typography.labelSmall, color = subColor)
    }
}


@Composable
private fun InlineKeyboardGrid(
    rows: List<List<com.maodouchat.data.model.InlineKeyboardButton>>,
    isOwnMessage: Boolean,
    messageId: String,
    onClick: ((String, String) -> Unit)?
) {
    if (rows.isEmpty() || onClick == null) return
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { btn ->
                    val bg = if (isOwnMessage) Color.White.copy(alpha = 0.18f) else Primary.copy(alpha = 0.10f)
                    val fg = if (isOwnMessage) Color.White else Primary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                val data = btn.callbackData.ifBlank { btn.text }
                                onClick(messageId, data)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btn.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 0.65 新功能：群主/管理员角色徽章。 */
@Composable
private fun RoleBadge(label: String, owner: Boolean) {
    val background = if (owner) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (owner) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = foreground,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
