package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 管理与审核 DTO：频道健康、AI 审计、举报、审核规则、风控事件。


@Serializable
data class AdminChannelHealthResponse(
    val openaiConfigured: Boolean,
    val turnConfigured: Boolean,
    val smtpConfigured: Boolean,
    val jwtConfigured: Boolean,
    val openaiModel: String = "",
    val turnUrlCount: Int = 0,
    val smtpHostMasked: String = ""
)


@Serializable
data class AiAuditLogResponse(
    val id: String,
    val chatId: String? = null,
    val feature: String,
    val model: String? = null,
    val status: String,
    val inputChars: Int = 0,
    val contextMessages: Int = 0,
    val durationMs: Long? = null,
    val error: String? = null,
    val createdAt: Long
)


@Serializable
data class CreateReportRequest(
    val targetType: String,
    val targetId: String,
    val chatId: String? = null,
    val messageId: String? = null,
    val reason: String,
    val description: String? = null
)


@Serializable
data class ReportResponse(
    val id: String,
    val reporterId: String,
    val targetType: String,
    val targetId: String,
    val chatId: String? = null,
    val messageId: String? = null,
    val reason: String,
    val description: String? = null,
    val status: String,
    val createdAt: Long,
    val reviewerId: String? = null,
    val resolutionNote: String? = null,
    val actionTaken: String? = null,
    val actionAt: Long? = null,
    val resolvedAt: Long? = null
)


@Serializable
data class UpdateReportStatusRequest(
    val status: String,
    val resolutionNote: String? = null
)


@Serializable
data class ApplyReportActionRequest(
    val action: String,
    val resolutionNote: String? = null
)


@Serializable
data class ModerationRuleResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val scope: String,
    val matchType: String,
    val pattern: String? = null,
    val action: String,
    val windowMs: Long = 0,
    val hitThreshold: Int = 0,
    val escalationAction: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val updatedAt: Long = 0
)


@Serializable
data class UpdateModerationRuleRequest(
    val name: String? = null,
    val scope: String? = null,
    val matchType: String? = null,
    val pattern: String? = null,
    val enabled: Boolean? = null,
    val action: String? = null,
    val hitThreshold: Int? = null,
    val windowMs: Long? = null,
    val escalationAction: String? = null,
    val priority: Int? = null
)


@Serializable
data class CreateModerationRuleRequest(
    val name: String,
    val scope: String = "ALL",
    val matchType: String = "KEYWORD",
    val pattern: String,
    val action: String = "WARN_MOD",
    val hitThreshold: Int = 0,
    val windowMs: Long = 0,
    val priority: Int = 100,
    val enabled: Boolean = true
)


@Serializable
data class RiskEventResponse(
    val id: String,
    val userId: String,
    val source: String,
    val ruleId: String? = null,
    val action: String,
    val matched: String? = null,
    val referenceId: String? = null,
    val needsReview: Boolean = false,
    val createdAt: Long
)
