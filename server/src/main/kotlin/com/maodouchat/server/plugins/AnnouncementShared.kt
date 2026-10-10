package com.maodouchat.server.plugins

import com.maodouchat.server.model.AnnouncementDto
import com.maodouchat.server.repository.AnnouncementRepository

// 公告两簇共用的行转 DTO（private→internal，零行为改动）。
internal fun AnnouncementRepository.AnnouncementRow.toDto(acked: Boolean): AnnouncementDto = AnnouncementDto(
    id = id, title = title, content = content, level = level,
    audience = targetAudience, tagId = targetTagId,
    startsAt = startsAt, expiresAt = expiresAt, status = status,
    createdBy = createdBy, createdAt = createdAt, updatedAt = updatedAt,
    publishedAt = publishedAt, cancelledAt = cancelledAt, acked = acked
)
