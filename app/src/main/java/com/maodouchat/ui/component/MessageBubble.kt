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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.TextWhite
import com.maodouchat.ui.theme.TextWhiteSecondary
import com.maodouchat.ui.theme.UnreadRed
import java.util.Locale

// 语音波形高度常量
internal val WaveHeights = listOf(0.3f, 0.7f, 1f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f)

/**
 * 9.272：语音波形高度按 message.id 派生确定性伪随机——每条语音拥有独有且稳定的波形
 *（TG 观感：每条语音波形不同；原固定序列全部语音同一形状）。LCG 保证同 id 恒定。
 */
internal fun waveHeightsFor(messageId: String): List<Float> {
    var seed = messageId.hashCode().toLong()
    return List(WaveHeights.size) {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        0.25f + ((seed ushr 33) % 76) / 100f
    }
}

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
    showSenderName: Boolean = false,
    isGroupEdge: Boolean = true,
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
    onStatusClick: ((Message) -> Unit)? = null,
    /** Allows the chat row to render the status in its shared action column. */
    showStatusIcon: Boolean = true
) {
    val message = if (isGroupChat) {
        message.copy(
            status = com.maodouchat.ui.screen.chatlist.ChatListReceiptPolicy.displayStatus(
                message.status,
                isGroup = true,
            )
        )
    } else {
        message
    }
    val presentation = remember(message) { MessagePresentationMapper.map(message) }
    when (presentation.type) {
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
                storedContent = presentation.body,
                senderDisplayName = senderName.orEmpty(),
                isDirectChat = !isGroupChat,
                templates = templates
            )
            SystemMessageRenderer(display, modifier)
        }
        MessageType.SYSTEM -> SystemMessageRenderer(presentation.body, modifier)
        MessageType.REVOKED -> SystemMessageRenderer(stringResource(R.string.chat_message_revoked_placeholder), modifier)
        MessageType.MARKDOWN, MessageType.TEXT -> TextBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, showSenderName, isGroupEdge, senderName,
            mentionedUserIds.ifEmpty { presentation.meta.mentions }, replyToPreview, onReply,
            onReplyPreviewClick, onBoundsMeasured, translationText, isTranslating, isAiAssisted || presentation.meta.aiAssisted, currentUserId, safetyWarning, onDismissSafety,
            onReactionClick, onPollVote, secretChatId, onInlineKeyboardClick, onContactCardClick, onSenderClick, onStatusClick,
            memberRole = memberRole,
            showStatusIcon = showStatusIcon
        )
        MessageType.IMAGE -> ImageBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onImageClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId, onViewOnceOpened, onRevealSpoiler, onStatusClick, showStatusIcon
        )
        MessageType.GIF -> ImageBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onImageClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId, onViewOnceOpened, onRevealSpoiler, onStatusClick, showStatusIcon
        )
        MessageType.STICKER -> StickerBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured, currentUserId, onReactionClick, onStatusClick,
            showStatusIcon
        )
        MessageType.LOCATION -> LocationBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured, currentUserId, onReactionClick, onStatusClick,
            showStatusIcon
        )
        MessageType.VOICE -> VoiceBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onBoundsMeasured,
            voiceTranscript ?: presentation.meta.voiceTranscript, isVoiceTranscribing, onRequestVoiceTranscript, onCopyVoiceTranscript,
            fileTransferProgress, fileTransferState,
            fileTransferError, onPauseFileTransfer, onResumeFileTransfer,
            onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, onStatusClick, showStatusIcon
        )
        MessageType.VIDEO -> VideoBubble(
            message, presentation, isOwnMessage, modifier, showAvatar, senderName, onVideoClick, onBoundsMeasured,
            fileTransferProgress, fileTransferState, fileTransferError,
            onPauseFileTransfer, onResumeFileTransfer, onCancelFileTransfer, onRequestMediaAttachment, mediaDownloadFailed,
            currentUserId, onReactionClick, secretChatId, onViewOnceOpened, onRevealSpoiler, onStatusClick, showStatusIcon
        )
        MessageType.FILE -> FileMessageRenderer(
            message,
            presentation,
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
            onStatusClick,
            showStatusIcon
        )
        MessageType.SK_DIST -> Unit
    }
}

data class ReplyPreview(val senderName: String, val preview: String)

@Composable
internal fun ReactionSummaryRow(
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
                color = if (reactedByMe) Primary else if (isOwnMessage) LocalSentBubbleContent.current else OnSurface,
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
                color = if (isOwnMessage) LocalSentBubbleContent.current else TextHint,
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

internal fun Modifier.captureBubbleBounds(onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?): Modifier {
    if (onBoundsMeasured == null) return this
    return onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        onBoundsMeasured(
            IntOffset(position.x.roundToInt(), position.y.roundToInt()),
            IntSize(coordinates.size.width, coordinates.size.height)
        )
    }
}

/**
 * 9.267：TG 式角尾——组尾消息（showAvatar 位）的尾角侧用小圆角：
 * 自己消息右下角 / 对方消息左下角；其余角保持主题形状（尊重用户自定义圆角风格）。
 */
@Composable
internal fun AttachmentTransferOverlay(
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
internal fun fileTransferStatusText(state: String, errorCode: String?): String = when (state) {
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

@Composable
internal fun MessageStatusIcon(
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

internal fun formatTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}

internal fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

/** 阅后即焚剩余时间文案；无期限或已过期返回 null。 */
@Composable
internal fun formatDisappearCountdown(expiresAt: Long?, nowMs: Long): String? {
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
internal fun DisappearCountdownLabel(
    expiresAt: Long?,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier
) {
    if (expiresAt == null || expiresAt <= 0L) return
    var nowMs by remember(expiresAt) { mutableLongStateOf(System.currentTimeMillis()) }
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
        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else Primary,
        modifier = modifier
    )
}

/**
 * 本机链接预览：开关关/无 URL/失败均静默不渲染。
 * 仅在消息正文含 http(s) URL 时拉取；Repository 进程缓存命中时无 loading 闪烁。
 */

@Composable
private fun MessageBubblePreview() {
    MaterialTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            SystemMessageRenderer("You nudged Alex Chen")
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

/** 0.65 新功能：群主/管理员角色徽章。 */
