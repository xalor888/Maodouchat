package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatListPreviewPolicy
import com.maodouchat.core.model.ChatMarkdown
import com.maodouchat.core.model.MessageType

// 会话列表行消息预览：锁定/密聊屏蔽优先，戳一戳与媒体标签次之，其余走正文可见文本；最终预览按定时 > 输入中 > 草稿 > 消息排序。
object ChatListItemPreviewPolicy {

    fun resolveMessagePreview(
        isLocked: Boolean,
        lockedPreview: String,
        secretBlocked: Boolean,
        secretPreview: String,
        lastMessageType: MessageType,
        lastMessage: String,
        nudgeLabel: String,
        mediaLabel: String?,
        encryptedPlaceholder: String,
    ): String {
        if (isLocked) return lockedPreview
        if (secretBlocked) return secretPreview
        if (lastMessageType == MessageType.NUDGE) return nudgeLabel
        mediaLabel?.let { return it }
        val stripped = ChatListPreviewPolicy.listVisibleText(lastMessage, encryptedPlaceholder)
        return if (stripped == encryptedPlaceholder) encryptedPlaceholder
        else ChatMarkdown.stripContactCardMarker(stripped)
    }

    fun resolveFinalPreview(
        typingPreview: String?,
        draftPreview: String?,
        messagePreview: String,
        scheduledLabel: String?,
    ): String = scheduledLabel ?: typingPreview ?: draftPreview ?: messagePreview
}
