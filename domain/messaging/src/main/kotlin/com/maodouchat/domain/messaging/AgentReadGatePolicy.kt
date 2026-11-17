package com.maodouchat.domain.messaging

// Agent 读门闩的错误文案与屏蔽集合，自 app 侧 AgentMessagingReadGates 收敛；
// 门闩判定与查库仍留在 app 侧。
object AgentReadGatePolicy {

    fun blankChatIdError(): String = "Error: chatId required"

    fun secretChatError(): String = "Error: secret chats cannot be accessed by AI"

    fun blockedIds(secret: Set<String>, locked: Set<String>): Set<String> = secret + locked
}
