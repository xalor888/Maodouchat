package com.maodouchat.ai.agent

/**
 * User-owned model endpoint. Stored only in EncryptedSharedPreferences on this device.
 * Never sent to the Maodou chat server.
 */
enum class LocalAiProtocol {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES
}

data class LocalAiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS,
    val anthropicVersion: String = "2023-06-01",
    val organization: String = "",
    val extraHeadersJson: String = "{}",
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int = 4_096,
    val contextWindowTokens: Int = 128_000,
    val historyMessageLimit: Int = 24,
    val timeoutSeconds: Int = 120,
    val stream: Boolean = true,
    val supportsVision: Boolean = false
) {
    /**
     * 判断当前模型是否具备视觉多模态能力。
     * 若显式设置为 true 则为 true；若为 false，则尝试匹配知名视觉模型命名规则。
     */
    fun hasVisionCapability(): Boolean {
        if (supportsVision) return true
        val m = model.trim().lowercase()
        return m.contains("vision") ||
            m.contains("4o") ||
            m.contains("claude-3") ||
            m.contains("gemini") ||
            m.contains("qwen-vl") ||
            m.contains("vl-") ||
            m.endsWith("-vl") ||
            m.contains("pixtral") ||
            m.contains("llava") ||
            m.contains("multimodal")
    }

    fun resolvedChatCompletionsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun resolvedResponsesUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/responses")) base else "$base/responses"
    }

    fun resolvedAnthropicUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/messages")) base else "$base/v1/messages"
    }

    fun clampedHistoryLimit(): Int = historyMessageLimit.coerceIn(4, 200)

    fun clampedMaxTokens(): Int = maxTokens.coerceIn(16, 128_000)

    fun clampedContextWindow(): Int = contextWindowTokens.coerceIn(1_024, 2_000_000)

    fun clampedTimeoutSeconds(): Int = timeoutSeconds.coerceIn(15, 300)
}

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

data class PendingAgentApproval(
    val call: AgentToolCall,
    val preview: String
)

sealed interface AgentTurnEvent {
    data class TextDelta(val text: String) : AgentTurnEvent
    data class AssistantFinal(val text: String) : AgentTurnEvent
    data class ToolStarted(val name: String) : AgentTurnEvent
    data class ToolFinished(val name: String, val result: String) : AgentTurnEvent
    data class NeedsApproval(val pending: PendingAgentApproval) : AgentTurnEvent
    data class Failed(val message: String) : AgentTurnEvent
}

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
