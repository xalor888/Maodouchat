package com.maodouchat.server.repository

// bot 命令定义簇（myCommands 读写/群内命令查询）：原 BotRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun BotRepository.listEnabledBotsInChat(chatId: String): List<BotRepository.ChatBotSummary> = transaction {
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
            BotRepository.ChatBotSummary(
                id = row[BotApps.id],
                username = row[BotApps.username],
                name = row[BotApps.name],
                enabled = true
            )
        }
        .sortedBy { it.username }
}

fun BotRepository.listCommandsForChat(chatId: String): List<BotRepository.ChatBotCommandItem> = transaction {
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
                BotRepository.ChatBotCommandItem(
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

fun BotRepository.findEnabledBotByUsername(username: String): BotRepository.BotDto? {
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

fun BotRepository.getMyCommands(botId: String): List<BotRepository.BotCommandDef> {
    return transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction emptyList()
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction emptyList()
        parseCommands(bot[BotApps.commandsJson])
    }
}

fun BotRepository.setMyCommands(botId: String, commands: List<BotRepository.BotCommandDef>): List<BotRepository.BotCommandDef>? {
    val normalized = normalizeCommands(commands) ?: return null
    val json = encodeCommands(normalized)
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction null
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
        val updated = BotApps.update({ BotApps.id eq botId }) {
            it[commandsJson] = json
            it[updatedAt] = now
        }
        if (updated == 1) normalized else null
    }
}

fun BotRepository.setMyDescription(botId: String, description: String?): BotRepository.BotDto? {
    val clean = description?.trim()?.take(500)
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction null
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
        val updated = BotApps.update({ BotApps.id eq botId }) {
            it[BotApps.description] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}

fun BotRepository.setMyName(botId: String, name: String): BotRepository.BotDto? {
    val clean = name.trim().take(120)
    if (clean.isBlank()) return null
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction null
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
        val updated = BotApps.update({ BotApps.id eq botId }) {
            it[BotApps.name] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        Users.update({ Users.id eq botId }) {
            it[Users.name] = clean
        }
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}

fun BotRepository.clearMyCommands(botId: String): Boolean {
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction false
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction false
        BotApps.update({ BotApps.id eq botId }) {
            it[commandsJson] = null
            it[updatedAt] = now
        } == 1
    }
}

internal fun BotRepository.normalizeCommands(commands: List<BotRepository.BotCommandDef>): List<BotRepository.BotCommandDef>? {
    if (commands.size > 100) return null
    val out = ArrayList<BotRepository.BotCommandDef>(commands.size)
    val seen = HashSet<String>()
    for (c in commands) {
        val command = c.command.trim().lowercase().removePrefix("/")
        val description = c.description.trim().take(256)
        if (!botCommandPattern.matches(command)) return null
        if (description.isBlank()) return null
        if (!seen.add(command)) return null
        out.add(BotRepository.BotCommandDef(command = command, description = description))
    }
    return out
}
