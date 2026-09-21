package com.maodouchat.ui.component

import com.maodouchat.util.RuntimeFlags
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.util.MediaCache
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.TextWhite

/**
 * 图片与视频消息气泡（G128 从 `MediaMessageBubbles.kt` 拆出，原 410 行）。
 *
 * 两个 Composable：`ImageBubble`（九宫格缩略图 / 点开大图 / 阅后即焚 / 发送态）与
 * `VideoBubble`（封面 + 时长角标 + 播放按钮 + 下载进度 / 播放态）。
 * 其余气泡（位置 / 贴纸 / 语音 / 投票 / 行内键盘）留在 `MediaMessageBubbles.kt`。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 图片/视频缓存经 `MediaCache`。纯搬移，不改判断。
 */

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
