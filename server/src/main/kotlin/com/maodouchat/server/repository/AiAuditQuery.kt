package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.model.AiAuditLogResponse
import java.time.LocalDate
import java.time.ZoneId
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

// AI 审计用户侧查询。

fun AiRepository.sumTokensForUserToday(userId: String): Long {
    return transaction {
        val startOfDay = startOfTodayMillis()
        TransactionManager.current().exec(
            "SELECT COALESCE(SUM(COALESCE(input_tokens,0) + COALESCE(output_tokens,0)),0) " +
                "FROM ai_audit_logs WHERE user_id = ? AND created_at >= ?",
            listOf(VarCharColumnType() to userId, LongColumnType() to startOfDay)
        ) { rs ->
            if (rs.next()) rs.getLong(1) else 0L
        } ?: 0L
    }
}


fun AiRepository.startOfTodayMillis(zone: ZoneId = ZoneId.systemDefault()): Long {
    val today = LocalDate.now(zone)
    return today.atStartOfDay(zone).toInstant().toEpochMilli()
}


fun AiRepository.getAuditLogs(userId: String, limit: Int = 50): List<AiAuditLogResponse> = transaction {
    AiAuditLogs.selectAll()
        .where { AiAuditLogs.userId eq userId }
        .orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
        .limit(limit.coerceIn(1, 100))
        .map {
            AiAuditLogResponse(
                id = it[AiAuditLogs.id],
                chatId = it[AiAuditLogs.chatId],
                feature = it[AiAuditLogs.feature],
                model = it[AiAuditLogs.model],
                status = it[AiAuditLogs.status],
                inputChars = it[AiAuditLogs.inputChars],
                contextMessages = it[AiAuditLogs.contextMessages],
                durationMs = it[AiAuditLogs.durationMs],
                error = it[AiAuditLogs.error],
                createdAt = it[AiAuditLogs.createdAt]
            )
        }
}
