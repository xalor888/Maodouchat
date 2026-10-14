package com.maodouchat.ai.agent

data class AgentChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList()
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class AgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val messages: List<AgentChatMessage>
)

enum class AgentOverlayMode {
    DISABLED,
    FOREGROUND_ONLY,
    ALWAYS
}
