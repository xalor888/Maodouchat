package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat

// 归档建议会话名：群名 → 首位参与者展示名 → 首位参与者名。
fun chatNameForSuggestion(chat: Chat?): String? {
    if (chat == null) return null
    return chat.groupName
        ?: chat.participants.firstOrNull()?.displayName
        ?: chat.participants.firstOrNull()?.name
}
