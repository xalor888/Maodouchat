package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll

// 单 bot 命令日志上限：超限按 FIFO 淘汰最旧。
internal const val MAX_COMMAND_LOGS_PER_BOT = 2_000L

// 须在事务内调用。
internal fun BotRepository.evictOldestCommandLogsLocked(botId: String) {
    val total = BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }.count()
    if (total < MAX_COMMAND_LOGS_PER_BOT) return
    val overflow = (total - MAX_COMMAND_LOGS_PER_BOT + 1L).coerceAtLeast(1L).toInt()
    val oldestIds = BotCommandLogs.selectAll()
        .where { BotCommandLogs.botId eq botId }
        .orderBy(BotCommandLogs.createdAt to SortOrder.ASC)
        .limit(overflow)
        .map { it[BotCommandLogs.id] }
    if (oldestIds.isNotEmpty()) {
        BotCommandLogs.deleteWhere { BotCommandLogs.id inList oldestIds }
    }
}
