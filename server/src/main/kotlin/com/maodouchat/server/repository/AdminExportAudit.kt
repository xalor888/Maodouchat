package com.maodouchat.server.repository

import com.maodouchat.server.db.AuditExportRecords
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminExportRepository.auditExportRows(scope: String, fromMs: Long, toMs: Long, limit: Int): List<List<Any?>> = transaction {
    when (scope) {
        "ADMIN_AUDIT" -> ModerationAuditLog.selectAll().where {
            (ModerationAuditLog.createdAt greaterEq fromMs) and (ModerationAuditLog.createdAt less toMs)
        }.orderBy(ModerationAuditLog.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[ModerationAuditLog.id], row[ModerationAuditLog.actorId], row[ModerationAuditLog.userId],
                    row[ModerationAuditLog.action], row[ModerationAuditLog.detail], row[ModerationAuditLog.createdAt],
                )
            }

        "RISK_EVENTS" -> RiskEvents.selectAll().where {
            (RiskEvents.createdAt greaterEq fromMs) and (RiskEvents.createdAt less toMs)
        }.orderBy(RiskEvents.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[RiskEvents.id], row[RiskEvents.userId], row[RiskEvents.sourceValue],
                    row[RiskEvents.ruleId], row[RiskEvents.action], row[RiskEvents.matched],
                    row[RiskEvents.referenceId], row[RiskEvents.needsReview], row[RiskEvents.createdAt],
                )
            }

        "ANNOUNCEMENTS" -> SystemAnnouncements.selectAll().where {
            (SystemAnnouncements.createdAt greaterEq fromMs) and (SystemAnnouncements.createdAt less toMs)
        }.orderBy(SystemAnnouncements.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[SystemAnnouncements.id], row[SystemAnnouncements.title], row[SystemAnnouncements.content],
                    row[SystemAnnouncements.level], row[SystemAnnouncements.targetAudience],
                    row[SystemAnnouncements.targetTagId], row[SystemAnnouncements.startsAt],
                    row[SystemAnnouncements.expiresAt], row[SystemAnnouncements.status],
                    row[SystemAnnouncements.createdBy], row[SystemAnnouncements.createdAt],
                    row[SystemAnnouncements.publishedAt], row[SystemAnnouncements.cancelledAt],
                )
            }

        "USER_TAGS" -> {
            val rows = UserTagAssignments.selectAll().where {
                (UserTagAssignments.createdAt greaterEq fromMs) and (UserTagAssignments.createdAt less toMs)
            }.orderBy(UserTagAssignments.createdAt to SortOrder.ASC)
                .limit(limit)
                .toList()
            // 8.48 修复 M11：批量回查标签名（此前逐赋值查询 → N+1）
            val tagIds = rows.map { it[UserTagAssignments.tagId] }.distinct()
            val tagNameById = if (tagIds.isEmpty()) {
                emptyMap()
            } else {
                UserTags.selectAll().where { UserTags.id inList tagIds }
                    .associate { it[UserTags.id] to it[UserTags.name] }
            }
            rows.map { row ->
                val tagName = tagNameById[row[UserTagAssignments.tagId]] ?: ""
                listOf(
                    row[UserTagAssignments.tagId], tagName, row[UserTagAssignments.userId],
                    row[UserTagAssignments.assignmentSource], row[UserTagAssignments.assignedBy],
                    row[UserTagAssignments.createdAt],
                )
            }
        }

        "RATE_LIMIT" -> RateLimitStatsSnapshots.selectAll().where {
            (RateLimitStatsSnapshots.bucketStartMs greaterEq fromMs) and
                (RateLimitStatsSnapshots.bucketStartMs less toMs)
        }.orderBy(RateLimitStatsSnapshots.bucketStartMs to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[RateLimitStatsSnapshots.bucketStartMs], row[RateLimitStatsSnapshots.allowed],
                    row[RateLimitStatsSnapshots.rejected], row[RateLimitStatsSnapshots.totalBuckets],
                    row[RateLimitStatsSnapshots.maxBuckets], row[RateLimitStatsSnapshots.maxPerMinute],
                    row[RateLimitStatsSnapshots.sampledAt],
                )
            }

        else -> emptyList()
    }
}

/** 审计导出追溯记录（9.140：rowCount 记真实行数）。 */
fun AdminExportRepository.recordAuditExport(
    actorId: String,
    scope: String,
    fromMs: Long,
    toMs: Long,
    rowCount: Long,
    fileRef: String,
): Unit = transaction {
    AuditExportRecords.insert {
        it[AuditExportRecords.id] = UUID.randomUUID().toString()
        it[AuditExportRecords.actorId] = actorId
        it[AuditExportRecords.scope] = scope
        it[AuditExportRecords.fromMs] = fromMs
        it[AuditExportRecords.toMs] = toMs
        it[AuditExportRecords.rowCount] = rowCount
        it[AuditExportRecords.fileRef] = fileRef
        it[AuditExportRecords.requestedAt] = System.currentTimeMillis()
    }
}
