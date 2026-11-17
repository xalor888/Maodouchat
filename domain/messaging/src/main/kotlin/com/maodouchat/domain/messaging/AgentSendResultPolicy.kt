package com.maodouchat.domain.messaging

// Agent 消息发送与草稿操作的结果文案，自 app 侧 AgentMessagingSendTools 收敛；
// 数据库/网关调用仍留在 app 侧。
object AgentSendResultPolicy {

    fun queued(messageId: String, isGroup: Boolean, chatId: String): String =
        "Queued $messageId to ${if (isGroup) "group" else "direct"} $chatId via E2EE outbox"

    fun draftCleared(chatId: String): String =
        "Cleared draft for $chatId"

    fun draftSaved(chatId: String, charCount: Int): String =
        "Saved draft for $chatId ($charCount chars)"
}
