package com.maodouchat.domain.messaging

// Agent 会话与联系人设置操作的结果文案，自 app 侧 AgentMessagingChatTools 收敛；
// 仓储调用仍留在 app 侧。
object AgentChatResultPolicy {

    fun nicknameResult(userId: String, nickname: String): String =
        if (nickname.isBlank()) "Cleared nickname for $userId" else "Set nickname for $userId"

    fun markedRead(itemId: String): String =
        "Marked read $itemId"
}
