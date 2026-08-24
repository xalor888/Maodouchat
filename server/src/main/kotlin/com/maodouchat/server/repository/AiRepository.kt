package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.model.AiAuditLogResponse
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.slf4j.LoggerFactory

class AiRepository {

    private val logger = LoggerFactory.getLogger(AiRepository::class.java)

    fun recordAudit(
        userId: String,
        chatId: String?,
        feature: String,
        model: String?,
        status: String,
        inputChars: Int,
        contextMessages: Int = 0,
        durationMs: Long? = null,
        error: String? = null,
        inputTokens: Long? = null,
        outputTokens: Long? = null
    ) {
        try {
            transaction {
                AiAuditLogs.insert {
                    it[id] = "ai_${UUID.randomUUID()}"
                    it[AiAuditLogs.userId] = userId
                    it[AiAuditLogs.chatId] = chatId?.takeIf(String::isNotBlank)
                    it[AiAuditLogs.feature] = feature.take(40)
                    it[AiAuditLogs.model] = model?.take(80)
                    it[AiAuditLogs.status] = status.take(30)
                    it[AiAuditLogs.inputChars] = inputChars.coerceAtLeast(0)
                    it[AiAuditLogs.contextMessages] = contextMessages.coerceAtLeast(0)
                    it[AiAuditLogs.durationMs] = durationMs
                    it[AiAuditLogs.error] = error?.take(200)
                    it[AiAuditLogs.inputTokens] = inputTokens
                    it[AiAuditLogs.outputTokens] = outputTokens
                    it[AiAuditLogs.createdAt] = System.currentTimeMillis()
                }
            }
        } catch (auditError: Exception) {
            logger.warn("recordAudit failed (best-effort): {}", auditError.message)
        }
    }

    fun purgeOldAuditLogs(retentionDays: Int = 90): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            AiAuditLogs.deleteWhere { AiAuditLogs.createdAt less cutoff }
        }
    }

    fun sumTokensForUserToday(userId: String): Long {
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

    fun startOfTodayMillis(zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun getAuditLogs(userId: String, limit: Int = 50): List<AiAuditLogResponse> = transaction {
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
}
