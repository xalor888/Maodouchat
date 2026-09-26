package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.util.MediaCache

/**
 * 语音消息气泡（G129 从 `MediaMessageBubbles.kt` 拆出，原 400 行）。
 *
 * 含波形、时长、播放/暂停、未播放红点、播放速度、转写文案。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

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
                        // 稳定尺寸：转写进度占位恒为 14dp + 6dp；转写开始/结束时行宽与正文宽度不再跳变
                        Box(
                            modifier = Modifier.width(20.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
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
                            }
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
