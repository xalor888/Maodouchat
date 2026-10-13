package com.maodouchat.server.repository

// bot 可用性判定簇：owner/bot 送达门禁（原 BotCommandDelivery 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal fun BotRepository.isOwnerDeliverable(ownerUserId: String, now: Long): Boolean {
    val owner = Users.selectAll().where { Users.id eq ownerUserId }.firstOrNull() ?: return false
    return owner[Users.deletedAt] == null &&
        owner[Users.suspendedUntil] <= now &&
        owner[Users.messageRestrictedUntil] <= now &&
        owner[Users.postRestrictedUntil] <= now
}


/** Transaction-local bot availability check for write paths outside BotRepository. */
internal fun BotRepository.isBotDeliverableInTx(botId: String, now: Long): Boolean {
    val bot = BotApps.selectAll()
        .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
        .forUpdate()
        .firstOrNull() ?: return false
    return isOwnerDeliverable(bot[BotApps.ownerUserId], now)
}


fun BotRepository.isBotDeliverable(botId: String): Boolean = transaction {
    isBotDeliverableInTx(botId, System.currentTimeMillis())
}
