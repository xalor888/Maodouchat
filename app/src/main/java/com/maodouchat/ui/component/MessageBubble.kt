package com.maodouchat.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import com.maodouchat.ui.screen.chatlist.ChatListReceiptPolicy

/**
 * 消息气泡类型分发入口（U03）。
 *
 * 共享 chrome（状态图标、反应、传输遮罩、波形等）见 [MessageBubbleChrome.kt]。
 * 正文渲染分别由 Text / Media / File / System 渲染器承担。
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
            status = ChatListReceiptPolicy.displayStatus(
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
