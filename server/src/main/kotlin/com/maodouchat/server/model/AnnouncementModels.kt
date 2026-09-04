package com.maodouchat.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateAnnouncementRequest(
    val title: String,
    val content: String,
    val level: String = "INFO",
    val audience: String = "ALL",
    val tagId: String? = null,
    val startsAt: Long? = null,
    val expiresAt: Long? = null,
    /** true 时入库 DRAFT（可删除、须 publish 才对用户可见）。缺省保持立即 ACTIVE/SCHEDULED。 */
    val draft: Boolean = false
)

@Serializable
data class UpdateAnnouncementRequest(
    val title: String? = null,
    val content: String? = null,
    val level: String? = null,
    val audience: String? = null,
    val tagId: String? = null,
    val startsAt: Long? = null,
    val expiresAt: Long? = null
)

@Serializable
data class AnnouncementDto(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val audience: String,
    val tagId: String?,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val createdBy: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?,
    val acked: Boolean = false
)

@Serializable
data class ActiveAnnouncementsResponse(
    val announcements: List<AnnouncementDto>,
    val serverTime: Long
)

@Serializable
data class AnnouncementAckResponse(val ok: Boolean, val announcementId: String)

@Serializable
data class AnnouncementStatsResponse(
    val id: String,
    val recipientCount: Long,
    val audience: String,
    val targetTagId: String?,
    val ackedCount: Long,
    val createdAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?
)
