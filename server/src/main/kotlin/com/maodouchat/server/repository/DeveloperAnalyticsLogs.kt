package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.model.BotLogEntry
import com.maodouchat.server.model.BotLogsResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 命令日志分页。`total` 是**过滤后的总行数**（不是本页条数）——
 * 客户端按 limit/offset 翻页时靠它判断是否还有下一页。
 */
fun DeveloperAnalyticsRepository.commandLogs(
    botId: String,
    commandFilter: String?,
    sinceMs: Long?,
    limit: Int,
    offset: Long,
): BotLogsResponse = transaction {
    var query = BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }
    if (commandFilter != null) query = query.andWhere { BotCommandLogs.command eq commandFilter }
    if (sinceMs != null && sinceMs > 0) {
        query = query.andWhere { BotCommandLogs.createdAt greater sinceMs }
    }
    val total = query.count().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val rows = query.orderBy(
        BotCommandLogs.createdAt to SortOrder.DESC,
        BotCommandLogs.id to SortOrder.DESC,
    )
        .limit(limit, offset)
        .map { row ->
            BotLogEntry(
                id = row[BotCommandLogs.id],
                command = row[BotCommandLogs.command],
                chatId = row[BotCommandLogs.chatId],
                userId = row[BotCommandLogs.userId],
                createdAt = row[BotCommandLogs.createdAt],
            )
        }
    BotLogsResponse(logs = rows, total = total)
}

