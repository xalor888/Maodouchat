package com.maodouchat.ai.agent.context

import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.ai.agent.AgentChatMessage
import com.maodouchat.ai.agent.AgentToolCall
import com.maodouchat.ai.agent.AgentToolPolicy
import com.maodouchat.ai.agent.LocalAiProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Encapsulates prompt construction, temporal anchoring, prompt injection sanitization,
 * and context window message budget management.
 */
class AgentContextBuilder(
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun buildInitialMessages(
        provider: LocalAiProvider,
        history: List<AgentChatMessage>,
        userText: String,
        styleHint: String?
    ): MutableList<AgentChatMessage> {
        val now = Instant.now().atZone(zoneId).format(timeFormatter)
        val systemPrompt = AgentToolPolicy.systemPrompt(now, styleHint)
        val systemMessage = AgentChatMessage(role = "system", content = systemPrompt)

        val working = mutableListOf<AgentChatMessage>()
        working += systemMessage

        val historyBudget = provider.clampedHistoryLimit()
        val trimmedHistory = history
            .filter { it.role != "system" }
            .takeLast(historyBudget)
        working += trimmedHistory

        // Sanitize and budget user input
        val safeUserText = userText.trim().take(AgentToolPolicy.MAX_TEXT_SEND_CHARS)
        working += AgentChatMessage(role = "user", content = safeUserText)
        return working
    }

    fun appendApprovedToolRound(
        working: MutableList<AgentChatMessage>,
        approvedCall: AgentToolCall,
        toolResult: String
    ) {
        working += AgentChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(approvedCall)
        )
        working += AgentChatMessage(
            role = "tool",
            content = toolResult,
            toolCallId = approvedCall.id,
            toolName = approvedCall.name
        )
    }

    fun annotatePrivilegedHallucination(response: String): String {
        return AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
            response.trim(),
            "助手不能执行未审批的特权动作，请以本机实际结果为准。"
        )
    }
}
