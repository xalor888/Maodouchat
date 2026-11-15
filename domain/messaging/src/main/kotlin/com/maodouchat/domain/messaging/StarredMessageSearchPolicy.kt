package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message

// 星标消息搜索与展示名解析：搜索过滤、行内发信人名与会话名三处同构逻辑的收敛；
// 标签（本人/群/成员）由调用方从字符串资源传入，策略只收纯判定。
object StarredMessageSearchPolicy {

    fun senderLabel(chat: Chat?, message: Message, currentUserId: String, meLabel: String, memberLabel: String): String =
        if (message.senderId == currentUserId) meLabel
        else chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName ?: memberLabel

    fun chatTitle(chat: Chat?, currentUserId: String, groupLabel: String, memberLabel: String): String {
        if (chat == null) return groupLabel
        if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() } ?: groupLabel
        val other = chat.participants.firstOrNull { it.id != currentUserId } ?: chat.participants.firstOrNull()
        return other?.displayName ?: memberLabel
    }

    // 与正文解析口径一致——meta 恒在末尾，取最后一个 <meta> 之前的内容做搜索预览。
    fun searchPreview(content: String): String = content.substringBeforeLast("<meta>").trim()

    fun matchesQuery(message: Message, scopeChat: Chat?, currentUserId: String, query: String, meLabel: String, groupLabel: String, memberLabel: String): Boolean {
        val sender = senderLabel(scopeChat, message, currentUserId, meLabel, memberLabel)
        val title = chatTitle(scopeChat, currentUserId, groupLabel, memberLabel)
        val preview = searchPreview(message.content)
        return sender.contains(query, ignoreCase = true) ||
            title.contains(query, ignoreCase = true) ||
            preview.contains(query, ignoreCase = true)
    }

    fun filter(messages: List<Message>, chatsById: Map<String, Chat>, fallbackChat: Chat?, currentUserId: String, query: String, meLabel: String, groupLabel: String, memberLabel: String): List<Message> {
        val q = query.trim()
        if (q.isEmpty()) return messages
        return messages.filter { message ->
            val scopeChat = chatsById[message.chatId] ?: fallbackChat
            matchesQuery(message, scopeChat, currentUserId, q, meLabel, groupLabel, memberLabel)
        }
    }
}
