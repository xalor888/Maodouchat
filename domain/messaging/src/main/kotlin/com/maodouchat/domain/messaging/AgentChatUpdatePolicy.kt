package com.maodouchat.domain.messaging

// 会话更新参数解析，自 app 侧 AgentMessagingChatTools.updateChat 收敛；
// 仓库写操作仍留在 app 侧。
object AgentChatUpdatePolicy {

    fun updateEntries(args: Map<String, String>): List<Pair<String, Boolean>> =
        listOf("pinned", "muted", "archived", "markedUnread").mapNotNull { key ->
            AgentToolArgs.parseBool(args[key])?.let { key to it }
        }

    fun formatUpdated(chatId: String, entries: List<Pair<String, Boolean>>): String =
        "Updated $chatId ${entries.joinToString(" ") { "${it.first}=${it.second}" }}"
}
