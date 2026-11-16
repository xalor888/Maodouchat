package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.session.SecretChatPolicy

// 会话列表行显示名：频道/群聊取群名、单聊取对方名，缺失时用调用方传入的默认文案；密聊统一打码。
object ChatListDisplayNamePolicy {

    fun resolve(
        chat: Chat,
        isSecret: Boolean,
        channelDefault: String,
        groupDefault: String,
        unknownLabel: String,
    ): String {
        val raw = when {
            chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: channelDefault
            chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: groupDefault
            else -> chat.participants.firstOrNull()?.displayName?.takeIf(String::isNotBlank) ?: unknownLabel
        }
        return if (isSecret || chat.isSecret) SecretChatPolicy.mosaicDisplayName(raw) else raw
    }
}
