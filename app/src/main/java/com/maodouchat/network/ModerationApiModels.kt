package com.maodouchat.network

import kotlinx.serialization.Serializable

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
data class UpdateReportStatusRequest(val status: String, val resolutionNote: String? = null)

@Serializable
data class ApplyReportActionRequest(val action: String, val resolutionNote: String? = null)

@Serializable
data class ModerationRuleResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val scope: String,
    val matchType: String,
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
    val enabled: Boolean? = null,
    val action: String? = null,
    val hitThreshold: Int? = null,
    val windowMs: Long? = null,
    val escalationAction: String? = null
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
