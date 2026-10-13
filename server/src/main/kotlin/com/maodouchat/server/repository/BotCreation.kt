package com.maodouchat.server.repository

// bot 创建簇：创建 bot 身份与应用行（原 BotLifecycleManagement 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import com.maodouchat.server.service.isUniqueViolation
import com.maodouchat.server.service.maxBotsPerUser
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun BotRepository.create(ownerUserId: String, name: String, username: String, description: String?): BotRepository.BotCreateResult {
    val n = name.trim().take(120)
    val u = normalizeUsername(username) ?: return BotRepository.BotCreateResult.InvalidInput
    if (n.isBlank()) return BotRepository.BotCreateResult.InvalidInput
    val maxBots = try {
        com.maodouchat.server.service.RuntimeConfigService.maxBotsPerUser()
    } catch (_: Exception) { 20 }
    val id = "bot_" + UUID.randomUUID().toString().replace("-", "").take(16)
    val token = generateToken()
    val now = System.currentTimeMillis()
    return try {
        transaction {
            val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
                ?: return@transaction BotRepository.BotCreateResult.OwnerInvalid
            if (owner[Users.deletedAt] != null) return@transaction BotRepository.BotCreateResult.OwnerInvalid
            if (owner[Users.suspendedUntil] > now) return@transaction BotRepository.BotCreateResult.OwnerInvalid
            val owned = BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }.count()
            if (owned >= maxBots.coerceAtLeast(0).toLong()) return@transaction BotRepository.BotCreateResult.MaxBotsReached
            if (BotApps.selectAll().where { BotApps.username eq u }.firstOrNull() != null) {
                return@transaction BotRepository.BotCreateResult.UsernameTaken
            }
            // Bot identity is also a Users row so message.senderId FK remains valid.
            Users.insert {
                it[Users.id] = id
                it[Users.email] = "$id@bots.maodouchat.local"
                it[Users.passwordHash] = "!" // unusable password
                it[Users.name] = n
                it[Users.searchable] = false
                it[Users.lastSeen] = now
            }
            BotApps.insert {
                it[BotApps.id] = id
                it[BotApps.ownerUserId] = ownerUserId
                it[BotApps.name] = n
                it[BotApps.username] = u
                it[BotApps.description] = description?.trim()?.take(500)
                it[BotApps.tokenHash] = hashToken(token)
                it[BotApps.tokenPrefix] = token.take(8)
                it[BotApps.webhookUrl] = null
                it[BotApps.enabled] = true
                it[BotApps.commandsJson] = encodeCommands(DEFAULT_COMMANDS)
                it[BotApps.createdAt] = now
                it[BotApps.updatedAt] = now
            }
            BotRepository.BotCreateResult.Success(
                BotApps.selectAll().where { BotApps.id eq id }.first().toDto().copy(tokenOnce = token)
            )
        }
    } catch (error: Exception) {
        if (isUniqueViolation(error)) BotRepository.BotCreateResult.UsernameTaken else throw error
    }
}
