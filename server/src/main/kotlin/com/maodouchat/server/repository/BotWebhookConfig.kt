package com.maodouchat.server.repository

// bot webhook 配置簇：设置/按 token 设置回调地址（原 BotLifecycleManagement 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun BotRepository.setWebhook(botId: String, ownerUserId: String, url: String?): BotDto? {
    val clean = url?.trim()?.take(500)
    if (!clean.isNullOrBlank() && !isAllowedWebhookUrl(clean)) {
        return null
    }
    val now = System.currentTimeMillis()
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > now) return@transaction null
        val updated = BotApps.update({
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }) {
            it[webhookUrl] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}


fun BotRepository.setWebhookByToken(botId: String, url: String?): BotDto? {
    val clean = url?.trim()?.take(500)
    if (!clean.isNullOrBlank() && !isAllowedWebhookUrl(clean)) {
        return null
    }
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction null
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
        val updated = BotApps.update({ BotApps.id eq botId }) {
            it[webhookUrl] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}
