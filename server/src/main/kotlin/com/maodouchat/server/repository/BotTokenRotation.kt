package com.maodouchat.server.repository

// bot 令牌轮换簇：重新生成 bot token（原 BotLifecycleManagement 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun BotRepository.regenerateToken(botId: String, ownerUserId: String): BotRepository.BotDto? {
    val token = generateToken()
    val now = System.currentTimeMillis()
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > now) return@transaction null
        val updated = BotApps.update({
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }) {
            it[tokenHash] = hashToken(token)
            it[tokenPrefix] = token.take(8)
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto().copy(tokenOnce = token)
    }
}
