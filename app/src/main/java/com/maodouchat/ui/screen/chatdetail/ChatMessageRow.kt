package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.MessageStatusIcon
import com.maodouchat.ui.component.MessageBubble
import com.maodouchat.ui.component.ReplyPreview
import com.maodouchat.ui.screen.chatlist.ChatListReceiptPolicy
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.listItemEnter
import com.maodouchat.ui.theme.Primary
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ChatMessageRowState(
    val message: Message,
    val isOwn: Boolean,
    val showAvatar: Boolean,
    val showSenderName: Boolean = false,
    val isGroupEdge: Boolean = true,
    val senderName: String?,
    val replyToPreview: ReplyPreview?,
    val isSearchHit: Boolean,
    val animateEntry: Boolean,
    val isAnimatingRemoval: Boolean,
    val isSelected: Boolean,
    val selectionMode: Boolean,
    val currentUserId: String,
    val isVoiceTranscribing: Boolean,
    val isTranslating: Boolean,
    val fileTransferProgress: Float?,
    val fileTransferState: String?,
    val fileTransferError: String?,
    val mediaDownloadFailed: Boolean,
    val safetyWarning: String?,
    val isGroupChat: Boolean = false,
    /** 群聊发送消息下方的已读进度环数据。 */
    val groupReadCount: ReadCountUi? = null,
    /** 0.65 新功能：发送者群内角色（OWNER/ADMIN），渲染名字旁徽章。 */
    val memberRole: String? = null,
    val secretChatId: String? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageRow(
    state: ChatMessageRowState,
    onImageClick: (Message) -> Unit,
    onVideoClick: (Message) -> Unit,
    onReply: (Message) -> Unit,
    onReplyPreviewClick: (Message) -> Unit,
    onBoundsMeasured: (IntOffset, IntSize) -> Unit,
    onFileClick: (Message) -> Unit,
    onPauseFileTransfer: (String) -> Unit,
    onResumeFileTransfer: (String) -> Unit,
    onCancelFileTransfer: (String) -> Unit,
    onRequestMediaAttachment: (String) -> Unit,
    onRequestVoiceTranscript: ((String) -> Unit)? = null,
    onCopyVoiceTranscript: ((String) -> Unit)? = null,
    onDismissSafety: (() -> Unit)?,
    onQuickReaction: (Message) -> Unit,
    onReactionClick: (Message, String) -> Unit = { _, _ -> },
    onPollVote: (String, Int) -> Unit = { _, _ -> },
    onViewOnceOpened: (String) -> Unit = {},
    onRevealSpoiler: (String) -> Unit = {},
    onInlineKeyboardClick: (String, String) -> Unit = { _, _ -> },
    onToggleSelection: (Message) -> Unit,
    onLongPress: (Message) -> Unit,
    /** 1.17：点击消息内联系人名片 → 打开该用户资料。 */
    onContactCardClick: ((String) -> Unit)? = null,
    /** 1.44：点击消息发送者名称 → 打开其资料。 */
    onSenderClick: ((String) -> Unit)? = null,
    /** 1.51：点击已读状态图标（✓✓）→ 打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val message = state.message
    val motion = LocalMotionSettings.current
    val highlightProgress by animateFloatAsState(
        targetValue = if (state.isSearchHit) 1f else 0f,
        animationSpec = motion.springSpec(dampingRatio = 0.62f, stiffness = 360f),
        label = "messageSearchHighlight"
    )
    val highlightScale by animateFloatAsState(
        targetValue = if (state.isSearchHit) 1.015f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.58f, stiffness = 420f),
        label = "messageSearchHighlightScale"
    )
    val shouldAnimateEntry = motion.animationsEnabled && state.animateEntry
    var visible by remember(message.id) { mutableStateOf(!shouldAnimateEntry) }
    LaunchedEffect(message.id, shouldAnimateEntry) { visible = true }

    AnimatedVisibility(
        visible = visible && !state.isAnimatingRemoval,
        enter = motion.listItemEnter() +
            scaleIn(tween(motion.duration(MotionTokens.Emphasized)), initialScale = 0.97f),
        exit = fadeOut(tween(motion.duration(MotionTokens.Fast))) +
            shrinkVertically(tween(motion.duration(MotionTokens.Fast))),
        modifier = modifier
    ) {
        if (message.type == MessageType.NUDGE) {
            MessageBubble(
                message = message,
                isOwnMessage = state.isOwn,
                senderName = state.senderName,
                memberRole = state.memberRole,
                isGroupChat = state.isGroupChat,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            return@AnimatedVisibility
        }

        val gestureEligible = isMessageGestureEligible(message.type)
        var swipeOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }
        var crossedReplyThreshold by remember(message.id) { mutableStateOf(false) }
        val replyThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
        val maxSwipePx = with(LocalDensity.current) { 76.dp.toPx() }
        val displayedSwipeOffset by animateFloatAsState(
            targetValue = swipeOffsetPx,
            animationSpec = motion.springSpec(dampingRatio = 0.82f, stiffness = 620f),
            label = "messageSwipeReply"
        )
        val replyProgress = (abs(displayedSwipeOffset) / replyThresholdPx).coerceIn(0f, 1f)
        val replyIconScale by animateFloatAsState(
            targetValue = if (abs(displayedSwipeOffset) >= replyThresholdPx) 1.12f else 0.7f + 0.3f * replyProgress,
            animationSpec = motion.springSpec(dampingRatio = 0.55f, stiffness = 480f),
            label = "replyIconScale"
        )
        val haptic = LocalHapticFeedback.current
        val hapticContext = LocalContext.current
        val replyActionLabel = stringResource(R.string.message_reply)
        val reactActionLabel = stringResource(R.string.chat_quick_react)
        val selectActionLabel = stringResource(R.string.chat_select_message)

        Box(modifier = Modifier.fillMaxWidth()) {
            // 左滑露出的回复图标（对标 Telegram 手势反馈）
            if (replyProgress > 0.05f && gestureEligible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .size(30.dp)
                        .graphicsLayer {
                            alpha = replyProgress
                            scaleX = replyIconScale
                            scaleY = replyIconScale
                        }
                        .background(
                            if (abs(displayedSwipeOffset) >= replyThresholdPx) Primary else Primary.copy(alpha = 0.55f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = replyActionLabel,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (state.isOwn) Alignment.End else Alignment.Start
            ) {
            MessageBubble(
                message = message,
                isOwnMessage = state.isOwn,
                showAvatar = state.showAvatar && !state.isOwn,
                showSenderName = state.showSenderName && !state.isOwn,
                isGroupEdge = state.isGroupEdge,
                senderName = state.senderName,
                memberRole = state.memberRole,
                isGroupChat = state.isGroupChat,
                mentionedUserIds = message.meta.mentions,
                replyToPreview = state.replyToPreview,
                onImageClick = onImageClick,
                onVideoClick = onVideoClick,
                onReply = null,
                onReplyPreviewClick = onReplyPreviewClick,
                onBoundsMeasured = onBoundsMeasured,
                onFileClick = onFileClick,
                voiceTranscript = message.meta.voiceTranscript,
                isVoiceTranscribing = state.isVoiceTranscribing,
                onRequestVoiceTranscript = onRequestVoiceTranscript,
                onCopyVoiceTranscript = onCopyVoiceTranscript,
                translationText = message.meta.displayedTranslation(),
                isTranslating = state.isTranslating,
                isAiAssisted = message.meta.aiAssisted,
                currentUserId = state.currentUserId,
                fileTransferProgress = state.fileTransferProgress,
                fileTransferState = state.fileTransferState,
                fileTransferError = state.fileTransferError,
                onPauseFileTransfer = onPauseFileTransfer,
                onResumeFileTransfer = onResumeFileTransfer,
                onCancelFileTransfer = onCancelFileTransfer,
                onRequestMediaAttachment = onRequestMediaAttachment,
                mediaDownloadFailed = state.mediaDownloadFailed,
                safetyWarning = state.safetyWarning,
                onDismissSafety = onDismissSafety,
                onReactionClick = { emoji -> onReactionClick(message, emoji) },
                onPollVote = onPollVote,
                onRevealSpoiler = onRevealSpoiler,
                onViewOnceOpened = onViewOnceOpened,
                onInlineKeyboardClick = { msgId, data ->
                    onInlineKeyboardClick(msgId, data)
                },
                onContactCardClick = onContactCardClick,
                onSenderClick = onSenderClick,
                showStatusIcon = false,
                onStatusClick = onStatusClick,
                secretChatId = state.secretChatId,
                modifier = Modifier
                    .offset { IntOffset(displayedSwipeOffset.roundToInt(), 0) }
                    .pointerInput(message.id, state.selectionMode, gestureEligible) {
                        if (gestureEligible && !state.selectionMode) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeOffsetPx = (swipeOffsetPx + dragAmount).coerceIn(-maxSwipePx, 0f)
                                    val crossed = shouldTriggerSwipeReply(swipeOffsetPx, replyThresholdPx)
                                    if (crossed && !crossedReplyThreshold) {
                                        com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                    }
                                    crossedReplyThreshold = crossed
                                },
                                onDragCancel = {
                                    swipeOffsetPx = 0f
                                    crossedReplyThreshold = false
                                },
                                onDragEnd = {
                                    if (shouldTriggerSwipeReply(swipeOffsetPx, replyThresholdPx)) {
                                        com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                        onReply(message)
                                    }
                                    swipeOffsetPx = 0f
                                    crossedReplyThreshold = false
                                }
                            )
                        }
                    }
                    .graphicsLayer {
                        scaleX = highlightScale
                        scaleY = highlightScale
                    }
                    .background(
                        if (state.isSelected) Primary.copy(alpha = 0.20f)
                        else Primary.copy(alpha = 0.16f * highlightProgress),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(vertical = 1.dp)
                    .semantics {
                        customActions = buildList {
                            if (gestureEligible) {
                                add(CustomAccessibilityAction(replyActionLabel) { onReply(message); true })
                                add(CustomAccessibilityAction(reactActionLabel) { onQuickReaction(message); true })
                            }
                            add(CustomAccessibilityAction(selectActionLabel) { onToggleSelection(message); true })
                        }
                    }
                    .combinedClickable(
                        onClick = { if (state.selectionMode) onToggleSelection(message) },
                        onDoubleClick = if (gestureEligible && !state.selectionMode) {
                            { onQuickReaction(message) }
                        } else null,
                        onLongClick = {
                            if (state.selectionMode) onToggleSelection(message) else onLongPress(message)
                        }
                    )
            )
                MessageActionRow(
                    message = message,
                    isOwnMessage = state.isOwn,
                    isGroupChat = state.isGroupChat,
                    groupReadCount = state.groupReadCount,
                    onReply = onReply,
                    onStatusClick = onStatusClick
                )
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    message: Message,
    isOwnMessage: Boolean,
    isGroupChat: Boolean,
    groupReadCount: ReadCountUi?,
    onReply: (Message) -> Unit,
    onStatusClick: ((Message) -> Unit)?
) {
    val showReply = message.type in setOf(MessageType.TEXT, MessageType.MARKDOWN)
    if (!isOwnMessage && !showReply && groupReadCount == null) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(
            top = 2.dp,
            start = if (isOwnMessage) 0.dp else 44.dp,
            end = if (isOwnMessage) 14.dp else 0.dp
        )
    ) {
        if (isOwnMessage) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clickable(enabled = onStatusClick != null) {
                        onStatusClick?.invoke(message)
                    },
                contentAlignment = Alignment.Center
            ) {
                MessageStatusIcon(
                    status = ChatListReceiptPolicy.displayStatus(
                        message.status,
                        isGroup = isGroupChat
                    )
                )
            }
        }

        if (showReply) {
            TextButton(
                onClick = { onReply(message) },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.message_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwnMessage) {
                        com.maodouchat.ui.theme.LocalSentBubbleContentSecondary.current
                    } else {
                        com.maodouchat.ui.theme.TextHint
                    }
                )
            }
        }

        groupReadCount?.let { count ->
            val readProgress = if (count.total > 0) {
                (count.read.toFloat() / count.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val readDescription = stringResource(
                R.string.chat_group_read_status,
                count.read,
                count.total
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .semantics { contentDescription = readDescription }
                    .clickable(enabled = onStatusClick != null) {
                        onStatusClick?.invoke(message)
                    },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { readProgress },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
