package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat

// 会话标题：群名 → 对方展示名 → 会话 id 前 12 位。
fun chatTitle(chat: Chat, ownerUserId: String): String {
    if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() } ?: chat.id.take(12)
    return chat.participants
        .firstOrNull { it.id != ownerUserId }
        ?.let { it.displayName.ifBlank { it.name } }
        ?: chat.groupName?.takeIf { it.isNotBlank() }
        ?: chat.id.take(12)
}
