package com.maodouchat.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateUserTagRequest(
    val name: String,
    val color: String = "#64748b",
    val description: String? = null,
    val riskLevel: String = "LOW"
)

@Serializable
data class UpdateUserTagRequest(
    val name: String? = null,
    val color: String? = null,
    val description: String? = null,
    val riskLevel: String? = null
)

@Serializable
data class AssignUserTagsRequest(val tagIds: List<String> = emptyList())

@Serializable
data class UserTagDto(
    val id: String,
    val name: String,
    val color: String,
    val description: String?,
    val isSystem: Boolean,
    val riskLevel: String,
    val createdAt: Long,
    val updatedAt: Long,
    val userCount: Long = 0
)

@Serializable
data class UserTagAssignmentDto(
    val userId: String,
    val tagId: String,
    val source: String,
    val assignedBy: String?,
    val createdAt: Long
)

@Serializable
data class RiskTagSummary(val tagId: String, val name: String, val riskLevel: String, val userCount: Long)

@Serializable
data class RiskTagSummaryResponse(val tags: List<RiskTagSummary>)
