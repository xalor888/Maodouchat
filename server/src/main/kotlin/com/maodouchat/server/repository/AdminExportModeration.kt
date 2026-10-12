package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.RiskEvents
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 审计元数据 only — no message bodies。 */
fun AdminExportRepository.moderationAudit(limit: Int): List<List<Any?>> = transaction {
    ModerationAuditLog.selectAll()
        .orderBy(
            ModerationAuditLog.createdAt to SortOrder.DESC,
            ModerationAuditLog.id to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[ModerationAuditLog.id],
                row[ModerationAuditLog.actorId].orEmpty(),
                row[ModerationAuditLog.userId].orEmpty(),
                row[ModerationAuditLog.action].take(40),
                row[ModerationAuditLog.detail].orEmpty().replace("\n", " ").take(160),
                row[ModerationAuditLog.createdAt].toString(),
            )
        }
}

/** 只有命令名 — no message bodies。 */
fun AdminExportRepository.botCommandStats(limit: Int): List<List<Any?>> = transaction {
    BotCommandLogs.selectAll()
        .orderBy(
            BotCommandLogs.createdAt to SortOrder.DESC,
            BotCommandLogs.id to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[BotCommandLogs.id],
                row[BotCommandLogs.botId],
                (row[BotCommandLogs.chatId] ?: "").take(40),
                (row[BotCommandLogs.userId] ?: "").take(40),
                row[BotCommandLogs.command].take(80),
                row[BotCommandLogs.createdAt].toString(),
            )
        }
}

fun AdminExportRepository.reports(limit: Int): List<List<Any?>> = transaction {
    Reports.selectAll()
        .orderBy(
            Reports.createdAt to SortOrder.DESC,
            Reports.id to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[Reports.id],
                row[Reports.reporterId],
                row[Reports.targetType],
                row[Reports.targetId],
                row[Reports.reason].take(200),
                row[Reports.status],
                row[Reports.createdAt].toString(),
            )
        }
}

/** 举报元数据 only — no message bodies / E2EE plaintext。 */
fun AdminExportRepository.reportsMeta(limit: Int): List<List<Any?>> = transaction {
    Reports.selectAll()
        .orderBy(
            Reports.createdAt to SortOrder.DESC,
            Reports.id to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[Reports.id],
                row[Reports.reporterId],
                row[Reports.targetType],
                row[Reports.targetId],
                row[Reports.chatId] ?: "",
                row[Reports.reason].take(60),
                row[Reports.status],
                row[Reports.actionTaken] ?: "",
                row[Reports.createdAt].toString(),
            )
        }
}

fun AdminExportRepository.riskEvents(limit: Int): List<List<Any?>> = transaction {
    RiskEvents.selectAll()
        .orderBy(
            RiskEvents.createdAt to SortOrder.DESC,
            RiskEvents.id to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[RiskEvents.id],
                row[RiskEvents.userId],
                row[RiskEvents.sourceValue],
                row[RiskEvents.action],
                (row[RiskEvents.matched] ?: "").replace("\n", " ").take(200),
                row[RiskEvents.needsReview].toString(),
                row[RiskEvents.createdAt].toString(),
            )
        }
}
