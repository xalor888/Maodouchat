package com.maodouchat.server.repository

/**
 * 系统公告仓储。公告为服务端广播的明文平台消息（非 E2EE 会话正文），
 * 不触碰 v2 envelopes / EncryptedAttachments 任何密文。
 */
class AnnouncementRepository {

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
}
