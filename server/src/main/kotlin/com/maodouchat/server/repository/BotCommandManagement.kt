package com.maodouchat.server.repository

// bot 命令管理簇：myCommands/描述/名称读写与规范化（原 BotCommands 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun BotRepository.getMyCommands(botId: String): List<BotCommandDef> {
    return transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction emptyList()
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction emptyList()
        parseCommands(bot[BotApps.commandsJson])
    }
}


fun BotRepository.setMyCommands(botId: String, commands: List<BotCommandDef>): List<BotCommandDef>? {
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


fun BotRepository.setMyDescription(botId: String, description: String?): BotDto? {
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


fun BotRepository.setMyName(botId: String, name: String): BotDto? {
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


internal fun BotRepository.normalizeCommands(commands: List<BotCommandDef>): List<BotCommandDef>? {
    if (commands.size > 100) return null
    val out = ArrayList<BotCommandDef>(commands.size)
    val seen = HashSet<String>()
    for (c in commands) {
        val command = c.command.trim().lowercase().removePrefix("/")
        val description = c.description.trim().take(256)
        if (!botCommandPattern.matches(command)) return null
        if (description.isBlank()) return null
        if (!seen.add(command)) return null
        out.add(BotCommandDef(command = command, description = description))
    }
    return out
}
