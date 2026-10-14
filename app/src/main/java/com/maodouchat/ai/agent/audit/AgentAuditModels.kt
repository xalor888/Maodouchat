package com.maodouchat.ai.agent.audit

enum class AuditEventType {
    PROMPT_DISPATCH,
    TOOL_REQUEST,
    TOOL_APPROVAL,
    TOOL_EXECUTED,
    TOOL_FAILED,
    DATA_EGRESS_BLOCKED,
    STREAM_INTERRUPTED
}

data class AgentAuditEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: AuditEventType,
    val toolName: String? = null,
    val details: String
)