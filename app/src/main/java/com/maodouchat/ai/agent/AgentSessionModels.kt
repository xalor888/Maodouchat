package com.maodouchat.ai.agent

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
