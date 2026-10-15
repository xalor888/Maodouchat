package com.maodouchat.server.repository

data class TagRow(
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

data class AssignmentRow(
    val userId: String,
    val tagId: String,
    val source: String,
    val assignedBy: String?,
    val createdAt: Long
)
