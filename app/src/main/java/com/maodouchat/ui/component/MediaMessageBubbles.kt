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

// 气泡形状
// 9.205：气泡形状改为 LocalBubbleShapes 提供（设置页可自定义），以下保留为旧引用兼容常量

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


// ─── ImageBubble ───
@Composable
internal fun ImageBubble(
    message: Message,
    presentation: MessagePresentation,
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
    onRevealSpoiler: ((String) -> Unit)? = null,
    /** 9.302：图片/GIF 气泡此前不渲染发送状态图标（文本/语音/文件都有），
     * 用户发图后看不到已发送/已送达反馈，误以为发图卡死。 */
    onStatusClick: ((Message) -> Unit)? = null,
    showStatusIcon: Boolean = true
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val attachment = presentation.attachment
    val viewOnce = attachment?.viewOnce == true
    val viewOnceLocked = attachment?.let { viewOnce && !isOwnMessage && it.viewOnceOpened } == true
    val spoilerHidden = attachment?.let { it.spoiler && !it.spoilerRevealed && !isOwnMessage } == true
    val mediaContent = attachment?.uri.orEmpty()
    val needsDownload = remember(presentation.id, attachment?.attachmentId, mediaContent, viewOnceLocked) {
        !viewOnceLocked && attachment?.attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    LaunchedEffect(presentation.id, attachment?.attachmentId, needsDownload) {
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
                    .clip(if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent else com.maodouchat.ui.theme.LocalBubbleShapes.current.received)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            com.maodouchat.ui.theme.LocalBubbleShapes.current.received
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
                    // 9.302：自己的媒体消息补上发送状态图标（与文本/语音/文件气泡一致）
                    if (isOwnMessage && showStatusIcon) {
                        Spacer(modifier = Modifier.width(3.dp))
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
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}


// ─── VoiceBubble ───
@Composable
internal fun VoiceBubble(
    message: Message,
    presentation: MessagePresentation,
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
    onStatusClick: ((Message) -> Unit)? = null,
    showStatusIcon: Boolean = true
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val attachment = presentation.attachment
    val mediaContent = attachment?.uri.orEmpty()
    val needsDownload = remember(presentation.id, attachment?.attachmentId, mediaContent) {
        attachment?.attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    LaunchedEffect(presentation.id, attachment?.attachmentId, needsDownload) {
        if (needsDownload) onRequestAttachment?.invoke(message.id)
    }
    val playerState by com.maodouchat.util.VoicePlayer.state.collectAsState()
    val isThisPlaying = playerState.messageId == message.id && playerState.isPlaying
    val isThisActive = playerState.messageId == message.id
    // 1.176：该语音是否已播放（气泡未读红点）
    val isVoicePlayed = remember(message.id) { com.maodouchat.util.VoicePlayedStore.isPlayed(context, message.id) }
    val progress = if (isThisActive) playerState.progress else 0f
    val knownDuration = playerState.takeIf { it.messageId == message.id && it.durationMs > 0L }?.durationMs
        ?: presentation.meta.voiceDurationMs
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
                    .clip(if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent else com.maodouchat.ui.theme.LocalBubbleShapes.current.received)
                    .background(if (isOwnMessage) Brush.linearGradient(com.maodouchat.ui.theme.ChatBubbleColorPalette.gradient(LocalChatBubbleColor.current)) else Brush.linearGradient(listOf(palette.chatBubbleReceived, palette.chatBubbleReceived)))
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            com.maodouchat.ui.theme.LocalBubbleShapes.current.received
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

                        // 模拟波形 + 实时进度覆盖（9.272：波形按 message.id 派生，每条语音独有）
                        val waveHeights = remember(message.id) { waveHeightsFor(message.id) }
                        Box(modifier = Modifier.weight(1f).height(28.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.CenterStart)
                            ) {
                                waveHeights.forEach { h ->
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
                            val activeCount = (waveHeights.size * progress).toInt().coerceIn(0, waveHeights.size)
                            if (activeCount > 0) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) {
                                    waveHeights.take(activeCount).forEach { h ->
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
                            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                        )
                        Text(
                            text = formatTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
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
                                color = if (isOwnMessage) LocalSentBubbleContent.current else Primary,
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
                                color = if (isOwnMessage) LocalSentBubbleContent.current else Primary,
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
                        if (isOwnMessage && showStatusIcon) {
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
                        color = if (isOwnMessage) LocalSentBubbleContent.current else Primary
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
                                color = if (isOwnMessage) LocalSentBubbleContent.current else Primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface,
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
                                    color = if (isOwnMessage) LocalSentBubbleContent.current else Primary,
                                    modifier = Modifier.clickable { expanded = !expanded }
                                )
                            }
                            if (onCopyVoiceTranscript != null) {
                                Text(
                                    text = stringResource(R.string.chat_copy_transcript),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOwnMessage) LocalSentBubbleContent.current else Primary,
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


// ─── VideoBubble ───
@Composable
internal fun VideoBubble(
    message: Message,
    presentation: MessagePresentation,
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
    secretChatId: String? = null,
    // 9.146：视频补齐阅后即焚/防剧透守卫（此前 VIDEO 完全缺失，ViewOncePolicy.supports 却包含 VIDEO）
    onViewOnceOpened: ((String) -> Unit)? = null,
    onRevealSpoiler: ((String) -> Unit)? = null,
    /** 9.302：与图片气泡一致，补上发送状态图标 */
    onStatusClick: ((Message) -> Unit)? = null,
    showStatusIcon: Boolean = true
) {
    val palette = LocalChatPalette.current
    val context = LocalContext.current
    val attachment = presentation.attachment
    val mediaContent = attachment?.uri.orEmpty()
    val needsDownload = remember(presentation.id, attachment?.attachmentId, mediaContent) {
        attachment?.attachmentId != null && !MediaCache.isReadableLocalUri(context, mediaContent)
    }
    val viewOnceLocked = attachment?.viewOnce == true && !isOwnMessage && attachment.viewOnceOpened
    val viewOnce = attachment?.viewOnce == true
    val spoilerHidden = attachment?.let { it.spoiler && !it.spoilerRevealed && !isOwnMessage } == true
    LaunchedEffect(presentation.id, attachment?.attachmentId, needsDownload) {
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
                    .clip(if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent else com.maodouchat.ui.theme.LocalBubbleShapes.current.received)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(if (!isOwnMessage) Modifier.border(1.dp, palette.chatBubbleReceivedBorder, com.maodouchat.ui.theme.LocalBubbleShapes.current.received) else Modifier)
                    .padding(4.dp)
                    .then(
                        // 9.146：与 ImageBubble 同构——阅后即焚锁定不可点；防剧透点击揭示；
                        // 正常点击打开视频并标记 view-once 已查看
                        if (viewOnceLocked) Modifier
                        else if (spoilerHidden) Modifier.clickable { onRevealSpoiler?.invoke(message.id) }
                        else if (onVideoClick != null && !needsDownload && transferState == null) Modifier.clickable {
                            onVideoClick(message)
                            if (viewOnce && !isOwnMessage) onViewOnceOpened?.invoke(message.id)
                        } else Modifier
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (viewOnceLocked) {
                        // 9.146：已查看的阅后即焚视频显示占位（与图片一致），不再渲染缩略图
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
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
                    // 9.146：防剧透视频叠遮罩（与图片一致）
                    if (spoilerHidden) {
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
                    } // end not viewOnceLocked
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
                    // 9.302：自己的媒体消息补上发送状态图标（与文本/语音/文件气泡一致）
                    if (isOwnMessage && showStatusIcon) {
                        Spacer(modifier = Modifier.width(3.dp))
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
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}


// ─── InteractivePollCard ───
@Composable
internal fun InteractivePollCard(
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
    var totalVoters by remember(pollId) { mutableIntStateOf(0) }
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

    val titleColor = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    val subColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
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



// ─── InlineKeyboardGrid ───
@Composable
internal fun InlineKeyboardGrid(
    rows: List<List<InlineKeyboardButtonPresentation>>,
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

