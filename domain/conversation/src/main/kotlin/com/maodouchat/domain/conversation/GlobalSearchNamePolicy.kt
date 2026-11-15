package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User

// 全局搜索的会话名/发信人名解析与命中正文归一化：只收纯判定，
// 字符串资源（未知会话/群聊/本人）由调用方传入。
object GlobalSearchNamePolicy {

    // 会话名：群名 → 首位参与者展示名 → 未知会话；群名/展示名空白时走回退。
    // 注意与 StarredMessageSearchPolicy.chatTitle 的差异：空会话回退未知会话
    // 而非群聊标签；单聊取首位参与者（不先排除自己）。
    fun chatName(chat: Chat?, unknownLabel: String, groupLabel: String): String {
        if (chat == null) return unknownLabel
        if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() } ?: groupLabel
        return chat.participants.firstOrNull()?.displayName?.takeIf { it.isNotBlank() }
            ?: unknownLabel
    }

    // 发信人名：本人 → “我”，否则按 id 找参与者展示名，找不到回退 senderId 本身。
    // currentUserId 可空：未登录时 snapshot().userId 为 null，此时无人是本人。
    fun senderName(
        senderId: String,
        currentUserId: String?,
        meLabel: String,
        participants: List<User>
    ): String {
        if (senderId == currentUserId) return meLabel
        return participants.firstOrNull { it.id == senderId }?.displayName ?: senderId
    }

    // 命中正文：换行折成一格空格并去首尾空白。
    fun hitText(searchableText: String): String =
        searchableText.replace('\n', ' ').trim()
}
