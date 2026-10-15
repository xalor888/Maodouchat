package com.maodouchat.server.repository

// bot 命令日志簇（写日志/分页读）：原 BotRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun BotRepository.logCommand(botId: String, chatId: String?, userId: String?, command: String) {
    transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate()
            .firstOrNull() ?: return@transaction
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction
        // 9.238：命令日志同样封顶——slash 命令洪泛不得让 bot_command_logs 无限增长；
        // 控制台只翻页读最近记录，FIFO 淘汰最旧不影响可见性
        evictOldestCommandLogsLocked(botId)
        BotCommandLogs.insert {
            it[id] = "bcl_" + UUID.randomUUID().toString().replace("-", "").take(16)
            it[BotCommandLogs.botId] = botId
            it[BotCommandLogs.chatId] = chatId
            it[BotCommandLogs.userId] = userId
            it[BotCommandLogs.command] = command.take(120)
            it[createdAt] = System.currentTimeMillis()
        }
    }
}

fun BotRepository.listCommandLogs(botId: String, limit: Int = 50, offset: Int = 0): List<CommandLogDto> = transaction {
    BotCommandLogs.selectAll()
        .where { BotCommandLogs.botId eq botId }
        .orderBy(
            BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            BotCommandLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
        )
        .limit(limit.coerceIn(1, 200), offset.toLong().coerceAtLeast(0))
        .map {
            CommandLogDto(
                id = it[BotCommandLogs.id],
                botId = it[BotCommandLogs.botId],
                chatId = it[BotCommandLogs.chatId],
                userId = it[BotCommandLogs.userId],
                command = it[BotCommandLogs.command],
                createdAt = it[BotCommandLogs.createdAt]
            )
        }
}
