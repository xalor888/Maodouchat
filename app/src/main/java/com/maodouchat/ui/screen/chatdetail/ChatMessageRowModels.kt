package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.ui.component.ReplyPreview

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
