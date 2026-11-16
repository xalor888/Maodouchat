package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiAuditLogResponse

// AI 隐私审计日志搜索：功能、状态、模型、错误、会话 id 任一含查询词即命中。
// 自 SettingsAiPrivacy 的 filteredAuditLogs 收敛。
object AiAuditLogFilterPolicy {

    fun matches(log: AiAuditLogResponse, query: String): Boolean =
        log.feature.contains(query, ignoreCase = true) ||
            log.status.contains(query, ignoreCase = true) ||
            log.model.orEmpty().contains(query, ignoreCase = true) ||
            log.error.orEmpty().contains(query, ignoreCase = true) ||
            log.chatId.orEmpty().contains(query, ignoreCase = true)

    fun filter(logs: List<AiAuditLogResponse>, query: String): List<AiAuditLogResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return logs
        return logs.filter { matches(it, trimmed) }
    }
}
