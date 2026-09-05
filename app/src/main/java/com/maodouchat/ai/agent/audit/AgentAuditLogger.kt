package com.maodouchat.ai.agent.audit

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Utility for masking sensitive tokens, keys, credentials, and PII before logging or outbound transmission.
 */
object AiDataMasker {
    private val TOKEN_REGEX = Regex("(?i)(token|bearer|key|password|secret|auth)[\\s:\"=]+([A-Za-z0-9_\\-\\.~]{6,})")
    private val PHONE_REGEX = Regex("(\\+?\\d{1,3}[-\\s]?)?\\d{11}")
    private val PRIVATE_KEY_REGEX = Regex("(?i)(-----BEGIN[ A-Z0-9_-]*PRIVATE KEY-----([\\s\\S]*?)-----END[ A-Z0-9_-]*PRIVATE KEY-----)")

    fun mask(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var result = input
        result = PRIVATE_KEY_REGEX.replace(result) { "[PRIVATE_KEY_REDACTED]" }
        result = TOKEN_REGEX.replace(result) { match ->
            val prefix = match.groupValues[1]
            val secret = match.groupValues[2]
            val masked = if (secret.length > 6) "${secret.take(3)}...${secret.takeLast(3)}" else "***"
            "$prefix=$masked"
        }
        result = PHONE_REGEX.replace(result) { match ->
            val phone = match.value
            if (phone.length >= 7) "${phone.take(3)}****${phone.takeLast(4)}" else "***"
        }
        return result
    }

    fun maskMap(map: Map<String, Any?>): Map<String, Any?> {
        return map.mapValues { (k, v) ->
            val keyLower = k.lowercase()
            when {
                keyLower.contains("key") || keyLower.contains("token") || keyLower.contains("password") || keyLower.contains("secret") ->
                    "***"
                v is String -> mask(v)
                v is Map<*, *> -> @Suppress("UNCHECKED_CAST") maskMap(v as Map<String, Any?>)
                else -> v
            }
        }
    }
}

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

interface AgentAuditLogger {
    fun log(eventType: AuditEventType, toolName: String? = null, details: String)
    fun getRecentEvents(limit: Int = 100): List<AgentAuditEvent>
}

class DefaultAgentAuditLogger(
    private val tag: String = "AgentAudit",
    private val maxEvents: Int = 500
) : AgentAuditLogger {
    private val events = CopyOnWriteArrayList<AgentAuditEvent>()

    override fun log(eventType: AuditEventType, toolName: String?, details: String) {
        val safeDetails = AiDataMasker.mask(details)
        val event = AgentAuditEvent(
            eventType = eventType,
            toolName = toolName,
            details = safeDetails
        )
        if (events.size >= maxEvents) {
            events.removeAt(0)
        }
        events.add(event)
        Log.i(tag, "[$eventType] tool=$toolName details=$safeDetails")
    }

    override fun getRecentEvents(limit: Int): List<AgentAuditEvent> {
        return events.takeLast(limit.coerceIn(1, maxEvents))
    }
}
