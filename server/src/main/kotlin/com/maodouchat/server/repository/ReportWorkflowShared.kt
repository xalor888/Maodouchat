package com.maodouchat.server.repository

import com.maodouchat.server.db.Reports
import com.maodouchat.server.model.ReportResponse
import org.jetbrains.exposed.sql.ResultRow

internal fun ResultRow.toResponse(): ReportResponse {
    return ReportResponse(
        id = this[Reports.id],
        reporterId = this[Reports.reporterId],
        targetType = this[Reports.targetType],
        targetId = this[Reports.targetId],
        chatId = this[Reports.chatId],
        messageId = this[Reports.messageId],
        reason = this[Reports.reason],
        description = this[Reports.description],
        status = this[Reports.status],
        createdAt = this[Reports.createdAt],
        reviewerId = this[Reports.reviewerId],
        resolutionNote = this[Reports.resolutionNote],
        actionTaken = this[Reports.actionTaken],
        actionAt = this[Reports.actionAt],
        resolvedAt = this[Reports.resolvedAt]
    )
}
