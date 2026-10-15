package com.maodouchat.server.repository

data class AnnouncementRow(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val targetAudience: String,
    val targetTagId: String?,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val createdBy: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?
)

data class AnnouncementStats(
    val recipientCount: Long,
    val audience: String,
    val targetTagId: String?,
    val createdAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?
)
