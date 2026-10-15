package com.maodouchat.server.repository

// bot 群内命令查询簇：群内可用 bot 与命令列表（原 BotCommands 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun BotRepository.listEnabledBotsInChat(chatId: String): List<ChatBotSummary> = transaction {
    val now = System.currentTimeMillis()
    val botIds = ChatParticipants.selectAll()
        .where { ChatParticipants.chatId eq chatId }
        .map { it[ChatParticipants.userId] }
        .filter { it.startsWith("bot_") }
    if (botIds.isEmpty()) return@transaction emptyList()
    BotApps.selectAll()
        .where { (BotApps.id inList botIds) and (BotApps.enabled eq true) }
        .mapNotNull { row ->
            if (!isOwnerDeliverable(row[BotApps.ownerUserId], now)) return@mapNotNull null
            ChatBotSummary(
                id = row[BotApps.id],
                username = row[BotApps.username],
                name = row[BotApps.name],
                enabled = true
            )
        }
        .sortedBy { it.username }
}


fun BotRepository.listCommandsForChat(chatId: String): List<ChatBotCommandItem> = transaction {
    val now = System.currentTimeMillis()
    val botIds = ChatParticipants.selectAll()
        .where { ChatParticipants.chatId eq chatId }
        .map { it[ChatParticipants.userId] }
        .filter { it.startsWith("bot_") }
    if (botIds.isEmpty()) return@transaction emptyList()
    BotApps.selectAll()
        .where { (BotApps.id inList botIds) and (BotApps.enabled eq true) }
        .flatMap { row ->
            if (!isOwnerDeliverable(row[BotApps.ownerUserId], now)) return@flatMap emptyList()
            parseCommands(row[BotApps.commandsJson]).map { cmd ->
                ChatBotCommandItem(
                    botId = row[BotApps.id],
                    username = row[BotApps.username],
                    name = row[BotApps.name],
                    command = cmd.command,
                    description = cmd.description
                )
            }
        }
        .sortedWith(compareBy({ it.username }, { it.command }))
}


fun BotRepository.findEnabledBotByUsername(username: String): BotDto? {
    val u = normalizeUsername(username) ?: return null
    return transaction {
        val now = System.currentTimeMillis()
        val row = BotApps.selectAll()
            .where { (BotApps.username eq u) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction null
        if (!isOwnerDeliverable(row[BotApps.ownerUserId], now)) return@transaction null
        row.toDto()
    }
}
