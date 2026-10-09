package com.maodouchat.ai.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 轮次消息装配：system 提示 + 历史窗口 + 用户文本 + 已审批调用的回填。
object AgentTurnMessages {
    fun base(
        provider: LocalAiProvider,
        history: List<AgentChatMessage>,
        userText: String,
        styleHint: String?
    ): MutableList<AgentChatMessage> {
        val now = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val working = mutableListOf<AgentChatMessage>()
        working += AgentChatMessage(
            role = "system",
            content = agentSystemPrompt(now, styleHint)
        )
        working += history.takeLast(provider.clampedHistoryLimit())
            .filter { it.role != "system" }
        working += AgentChatMessage(role = "user", content = userText.take(4_000))
        return working
    }

    suspend fun prefillApprovedCall(
        working: MutableList<AgentChatMessage>,
        approvedCall: AgentToolCall,
        executeTool: suspend (String, String) -> String
    ): AgentTurnEvent.ToolFinished {
        val result = executeTool(approvedCall.name, approvedCall.argumentsJson)
        working += AgentChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(approvedCall)
        )
        working += AgentChatMessage(
            role = "tool",
            content = result,
            toolCallId = approvedCall.id,
            toolName = approvedCall.name
        )
        return AgentTurnEvent.ToolFinished(approvedCall.name, result)
    }
}
