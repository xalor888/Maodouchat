package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.MotionSettings
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.component.ReplyPreview

/**
 * 气泡在窗口中的位置与尺寸（G85 从 `ChatDetailRoute.kt` 连同 item 渲染一起搬来）。
 *
 * 粒子删除动效需要知道「哪个气泡碎在哪」——`onBoundsMeasured` 回报后由 Route 存进 map，
 * `startParticleEffect` 再取出。原为 Route 文件的 private data class，
 * 随渲染一起搬到本文件并改 `internal`（internal Composable 不能暴露 private-in-file 类型）。
 */
internal data class BubbleBounds(
    val offset: androidx.compose.ui.unit.IntOffset,
    val size: androidx.compose.ui.unit.IntSize
)

/**
 * 主时间线的**单条 item** 渲染（G85 从 `ChatDetailRoute.kt` 拆出，原 231 行）。
 *
 * 刻意做成 `LazyItemScope` 的扩展函数：`Modifier.animateItem` 是 `LazyItemScope` 的成员，
 * 只有待在 `itemsIndexed` 的 lambda 里才有 placement 动画。把它降级成普通 Composable
 * 会让列表重排时的位移动画**静默消失**——所以用类型系统强制「只能在 LazyColumn 内调用」。
 *
 * 覆盖 `ChatItem` 三种类型：日期分隔胶囊、未读分隔线、消息气泡。
 * Msg 分支内含既有全部判断：选中态、已读数、翻译中、语音转写中、附件进度/错误、
 * 剧透揭示、投票、机器人回调、@提及高亮、阅后即焚标记。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所有输入经参数显式传入。纯搬移，不改判断。
 *
 * 参数按「一整块 state」传入而不是拆成 30 个字段：调用方（Route）本来就持有
 * `ChatDetailUiState`，拆字段只会让签名膨胀且容易漏传。`viewModel` 同理——
 * 但**只用它做回调**，不在本文件里读它的任何状态。
 *
 * @param items 已按时间倒序排好的列表项（`reversedChatItems`）
 * @param state 聊天 UI 状态
 * @param onBubblePlaced 气泡布局完成后回报坐标（粒子动效需要）
 */
@Composable
internal fun LazyItemScope.ChatDetailTimelineItem(
    index: Int,
    item: ChatItem,
    state: ChatDetailUiState,
    listState: LazyListState,
    motion: MotionSettings,
    selectedMessageIds: Set<String>,
    messageSelectionMode: Boolean,
    animatingMessageId: String?,
    searchResults: List<Message>,
    searchIndex: Int,
    showSearchBar: Boolean,
    viewModel: ChatDetailViewModel,
    onBubblePlaced: (String, BubbleBounds) -> Unit,
    onBubbleRemoved: (String) -> Unit,
    allItems: List<ChatItem>,
    localSafetyEnabled: Boolean,
    onShowFullscreenImage: (Message) -> Unit,
    onShowFullscreenVideo: (Message) -> Unit,
    onCopyTranscript: (String) -> Unit,
    onReplyTo: (Message) -> Unit,
    onDismissSafetyForMessage: (String) -> Unit,
    onToggleSelection: (Set<String>) -> Unit,
    onRetryMessage: (Message) -> Unit,
    onMessageActions: (Message) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShowReadReceipts: (Message) -> Unit,
    navigationHighlightMessageId: String?,
    dismissedSafetyMessageIds: Set<String>,
    messagesById: Map<String, Message>,
    resolveSenderName: (Message, Boolean) -> String?,
) {
    // 这是**单条 item** 的渲染：Route 仍在 LazyColumn 里调 itemsIndexed，
    // 把 (index, item) 传进来——因为 `Modifier.animateItem` 是 LazyItemScope 的扩展，
    // 只有待在 itemsIndexed 的 lambda 里才能用，搬到普通 Composable 会丢 placement 动画。
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewImageLabel = stringResource(R.string.message_preview_image)
    val previewGifLabel = stringResource(R.string.message_preview_gif)
    val previewStickerLabel = stringResource(R.string.message_preview_sticker)
    val previewVoiceLabel = stringResource(R.string.message_preview_voice)
    val previewVideoLabel = stringResource(R.string.message_preview_video)
    val previewFileLabel = stringResource(R.string.message_preview_file)
    val previewLocationLabel = stringResource(R.string.message_preview_location)
    val previewEncryptedLabel = stringResource(R.string.message_preview_encrypted)
    val transcriptCopiedTip = stringResource(R.string.chat_transcript_copied)
    val contactCardTapHint = stringResource(R.string.chat_contact_card_tap_hint)
    val itemPlacementSpec = motion.listItemPlacementSpec()
    when (item) {
    is ChatItem.DateSeparator -> {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(
                if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec)
                else Modifier
            ),
            contentAlignment = Alignment.Center) {
            // 9.270：TG 式日期胶囊——全胶囊形 + 加深半透明（悬浮在壁纸上的磨砂感，
            // 原 12dp 圆角矩形 + 淡半透明），字号收紧更精致
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .background(
                        LocalChatPalette.current.systemMessageBackground.copy(alpha = 0.32f),
                        RoundedCornerShape(percent = 50)
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
    is ChatItem.UnreadSeparator -> {
        // 1.03：「以下为未读消息」分隔线；1.74：点击跳转到第一条未读消息
        // 9.271：TG 式未读分隔条——全宽胶囊条 + 白字（TG 观感，原红线夹文字微信式）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .then(if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec) else Modifier)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                .clickable { viewModel.jumpToMessage(item.messageId) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_unread_divider),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                modifier = Modifier.padding(vertical = 5.dp)
            )
        }
    }
    is ChatItem.Msg -> {
        val message = item.message
        DisposableEffect(message.id) {
            onDispose { onBubbleRemoved(message.id) }
        }
        val isOwn = message.senderId == state.currentUserId
        val isSearchHit = searchResults.getOrNull(searchIndex)?.id == message.id ||
            navigationHighlightMessageId == message.id
        val meta = remember(message.id, message.content) { message.parsedMeta() }
        val displayContent = remember(message.id, message.content) { message.parsedContent() }
        val displayMessage = remember(message, displayContent, meta) {
            message.copy(content = displayContent, meta = meta)
        }
        val canShowSafety = (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
            !isOwn &&
            message.id !in dismissedSafetyMessageIds
        val groupReadCount = if (
            ReadReceiptPolicy.shouldShowGroupReadCount(
                isGroup = state.chatIsGroup,
                isOwnMessage = isOwn,
                viewerRole = state.myMemberRole,
            )
        ) {
            state.groupReadCounts[message.id]
        } else null
        // 9.264：TG 式分组密度——同一发送者 6 分钟内的连续消息紧凑成组（3dp），
        // 换人/超时/跨分隔符额外补 5dp（总 8dp）；reverseLayout 下时间上更早的
        // 消息在 index+1，本项 top padding 即与它的间隙
        val prevItem = allItems.getOrNull(index + 1)
        val groupedWithPrev = prevItem is ChatItem.Msg &&
            prevItem.message.senderId == message.senderId &&
            kotlin.math.abs(message.timestamp - prevItem.message.timestamp) < 6L * 60L * 1000L
        Column(Modifier.fillMaxWidth().padding(top = if (groupedWithPrev) 0.dp else 5.dp)) {
        ChatMessageRow(
            state = ChatMessageRowState(
                message = displayMessage,
                isOwn = isOwn,
                showAvatar = item.showAvatar,
                showSenderName = item.showSenderName,
                isGroupEdge = item.isGroupEdge,
                senderName = resolveSenderName(message, isOwn),
                replyToPreview = meta.replyToId?.let(messagesById::get)?.let {
                    ReplyPreview(
                        senderName = resolveSenderName(it, false) ?: "",
                        preview = MessagePreviewText.replyOrQuote(
                            message = it,
                            mediaLabel = { type ->
                                when (type) {
                                    MessageType.IMAGE -> previewImageLabel
                                    MessageType.GIF -> previewGifLabel
                                    MessageType.STICKER -> previewStickerLabel
                                    MessageType.VOICE -> previewVoiceLabel
                                    MessageType.VIDEO -> previewVideoLabel
                                    MessageType.FILE -> previewFileLabel
                                    MessageType.LOCATION -> previewLocationLabel
                                    else -> previewEncryptedLabel
                                }
                            },
                            encryptedPlaceholder = previewEncryptedLabel,
                        ).take(60)
                    )
                },
                isSearchHit = isSearchHit,
                animateEntry = index == 0,
                isAnimatingRemoval = message.id == animatingMessageId,
                isSelected = message.id in selectedMessageIds,
                selectionMode = messageSelectionMode,
                currentUserId = state.currentUserId,
                isVoiceTranscribing = message.id in state.transcribingVoiceMessageIds,
                isTranslating = message.id in state.translatingMessageIds,
                fileTransferProgress = state.fileTransferProgress[message.id],
                fileTransferState = state.fileTransferStates[message.id]
                    ?: AttachmentTransferState.PREPARING.takeIf { message.id in state.preparingAttachmentMessageIds },
                fileTransferError = state.fileTransferErrors[message.id],
                mediaDownloadFailed = message.id in state.mediaDownloadErrorMessageIds,
                safetyWarning = if (canShowSafety) messageSafetyWarning(displayContent, localSafetyEnabled) else null,
                isGroupChat = state.chat?.isGroup == true,
                groupReadCount = groupReadCount,
                // 0.65 新功能：发送者群内角色（群主/管理员徽章）
                memberRole = if (state.chat?.isGroup == true) state.memberRoleByUser[message.senderId] else null,
                secretChatId = if (state.isSecretChat == true) state.chat?.id else null,
            ),
            onImageClick = { onShowFullscreenImage(it) },
            onVideoClick = { onShowFullscreenVideo(it) },
            onReply = {
                onReplyTo(message)
                // 1.47：群聊回复时自动 @ 发送者（输入未包含时前置，对标微信/QQ）
                if (state.chatIsGroup && message.senderId != state.currentUserId) {
                    val senderName = resolveSenderName(message, false)
                    if (!senderName.isNullOrBlank()) {
                        val mentionToken = "@$senderName"
                        if (!state.inputText.contains(mentionToken)) {
                            val base = state.inputText.trimEnd()
                            viewModel.onInputChange(if (base.isEmpty()) "$mentionToken " else "$base $mentionToken ")
                        }
                    }
                }
            },
            onReplyPreviewClick = { target ->
                // 点击引用预览跳转到被回复的消息（id 在 meta 中，而非当前消息自身）
                val replyTo = target.parsedMeta().replyToId
                if (!replyTo.isNullOrBlank()) viewModel.jumpToMessage(replyTo)
            },
            onBoundsMeasured = { offset, size -> onBubblePlaced(message.id, BubbleBounds(offset, size)) },
            onFileClick = { viewModel.requestOpenFile(it.id) },
            onPauseFileTransfer = viewModel::pauseFileTransfer,
            onResumeFileTransfer = viewModel::resumeFileTransfer,
            onCancelFileTransfer = viewModel::cancelFileTransfer,
            onRequestMediaAttachment = viewModel::requestMediaAttachment,
            onRequestVoiceTranscript = { id ->
                viewModel.requestVoiceTranscription(id)
            },
            onCopyVoiceTranscript = { transcript ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                onCopyTranscript(transcript)
                Toast.makeText(context, transcriptCopiedTip, Toast.LENGTH_SHORT).show()
            },
            onDismissSafety = if (canShowSafety) {
                { onDismissSafetyForMessage(message.id) }
            } else null,
            onQuickReaction = { viewModel.setMessageReaction(it.id, DEFAULT_QUICK_REACTION) },
            onReactionClick = { msg, emoji -> viewModel.setMessageReaction(msg.id, emoji) },
            onPollVote = { pollId, idx -> viewModel.votePoll(pollId, idx) },
            onRevealSpoiler = { viewModel.revealSpoilerMedia(it) },
            onViewOnceOpened = { id -> viewModel.markViewOnceOpened(id) },
            onInlineKeyboardClick = { msgId, data ->
                val botId = messagesById[msgId]?.senderId
                    ?: state.messages.firstOrNull { it.id == msgId }?.senderId
                if (!botId.isNullOrBlank()) viewModel.sendBotCallback(msgId, botId, data)
            },
            onToggleSelection = {
                onToggleSelection(toggleMessageSelection(selectedMessageIds, it.id))
            },
            onLongPress = {
                if (it.status == com.maodouchat.data.model.MessageStatus.FAILED && isOwn) onRetryMessage(message)
                else onMessageActions(message)
            },
            // 1.17：点击消息内名片 → 打开对方资料（未接线时提示）
            onContactCardClick = { userId ->
                if (onOpenProfile != null) {
                    onOpenProfile(userId)
                } else {
                    Toast.makeText(context, contactCardTapHint, Toast.LENGTH_SHORT).show()
                }
            },
            // 1.44：点击消息发送者名称 → 打开其资料
            onSenderClick = { userId ->
                if (onOpenProfile != null) {
                    onOpenProfile(userId)
                } else {
                    Toast.makeText(context, contactCardTapHint, Toast.LENGTH_SHORT).show()
                }
            },
            // 1.51：点击已读状态图标 → 打开阅读详情（仅自己消息）
            onStatusClick = { msg ->
                if (ReadReceiptPolicy.canViewReceipts(
                        viewerId = state.currentUserId,
                        senderId = msg.senderId,
                        isGroup = state.chatIsGroup,
                        viewerRole = state.myMemberRole,
                    )
                ) {
                    onShowReadReceipts(msg)
                    viewModel.loadReadReceipts(msg.id)
                }
            },
            modifier = if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec) else Modifier
        )
        }
    }
    }
}
