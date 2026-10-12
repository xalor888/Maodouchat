package com.maodouchat.server.service

import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.model.AdminAuditLogResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun OperationsQueryService.auditLogs(limit: Int, offset: Long, action: String?, query: String?): List<AdminAuditLogResponse> =
    transaction {
        val base = ModerationAuditLog.selectAll()
        if (action != null) {
            base.andWhere { ModerationAuditLog.action eq action }
        }
        if (query != null) {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%${escaped.lowercase()}%"
            base.andWhere {
                (ModerationAuditLog.actorId.lowerCase() like pattern) or
                    (ModerationAuditLog.userId.lowerCase() like pattern) or
                    (ModerationAuditLog.detail.lowerCase() like pattern) or
                    (ModerationAuditLog.action.lowerCase() like pattern)
            }
        }
        base.orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toAuditLogResponse() }
    }
fun OperationsQueryService.auditLogsExport(limit: Int, offset: Int): List<AdminAuditLogResponse> = transaction {
    ModerationAuditLog.selectAll()
        .orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
        .limit(limit, offset.toLong())
        .map { it.toAuditLogResponse() }
}
private fun org.jetbrains.exposed.sql.ResultRow.toAuditLogResponse() = AdminAuditLogResponse(
    id = this[ModerationAuditLog.id],
    actorId = this[ModerationAuditLog.actorId],
    targetUserId = this[ModerationAuditLog.userId],
    action = this[ModerationAuditLog.action],
    detail = this[ModerationAuditLog.detail],
    createdAt = this[ModerationAuditLog.createdAt]
)
