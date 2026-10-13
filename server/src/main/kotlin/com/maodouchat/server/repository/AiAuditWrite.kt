package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

// AI 审计写入与过期清理。

private val logger = org.slf4j.LoggerFactory.getLogger(AiRepository::class.java)

fun AiRepository.recordAudit(
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


fun AiRepository.purgeOldAuditLogs(retentionDays: Int = 90): Int {
    val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
    return transaction {
        AiAuditLogs.deleteWhere { AiAuditLogs.createdAt less cutoff }
    }
}
