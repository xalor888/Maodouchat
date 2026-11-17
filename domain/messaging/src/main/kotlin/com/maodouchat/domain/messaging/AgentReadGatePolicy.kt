package com.maodouchat.domain.messaging

// Agent 读门闩的错误文案与屏蔽集合，自 app 侧 AgentMessagingReadGates 收敛；
// 门闩判定与查库仍留在 app 侧。查无会话/必填查询/必填动态 id 三项错误
// 自 ai/agent 读簇收敛。
object AgentReadGatePolicy {

    fun blankChatIdError(): String = "Error: chatId required"

    fun secretChatError(): String = "Error: secret chats cannot be accessed by AI"

    fun chatNotFoundError(): String = "Error: chat not found"

    fun blankQueryError(): String = "Error: query required"

    fun blankPostIdError(): String = "Error: postId required"

    fun blockedIds(secret: Set<String>, locked: Set<String>): Set<String> = secret + locked
}
