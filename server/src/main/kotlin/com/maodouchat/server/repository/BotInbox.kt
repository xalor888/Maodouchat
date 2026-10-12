package com.maodouchat.server.repository

// bot 更新收件箱簇（入队/拉取/确认/计数）：原 BotRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotUpdateInbox
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun BotRepository.countPendingUpdates(botId: String, offset: Long = 0): Long = transaction {
    val now = System.currentTimeMillis()
    val bot = BotApps.selectAll()
        .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
        .firstOrNull() ?: return@transaction 0L
    if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction 0L
    BotUpdateInbox.selectAll()
        .where {
            (BotUpdateInbox.botId eq botId) and
                (BotUpdateInbox.id greater offset.coerceAtLeast(0L))
        }
        .count()
}

fun BotRepository.enqueueUpdate(botId: String, updateJson: String) {
    if (updateJson.length > MAX_UPDATE_JSON_CHARS) {
        logger.warn("Bot update json too large ({} chars), dropping for {}", updateJson.length, botId)
        return
    }
    transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate()
            .firstOrNull() ?: return@transaction
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction
        evictOldestInboxLocked(botId)
        BotUpdateInbox.insert {
            it[BotUpdateInbox.botId] = botId
            it[BotUpdateInbox.updateJson] = updateJson
            it[createdAt] = now
        }
    }
}

fun BotRepository.getUpdates(botId: String, offset: Long = 0, limit: Int = 50): List<Pair<Long, String>> = transaction {
    val now = System.currentTimeMillis()
    val bot = BotApps.selectAll()
        .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
        .firstOrNull() ?: return@transaction emptyList()
    if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction emptyList()
    BotUpdateInbox.selectAll()
        .where {
            (BotUpdateInbox.botId eq botId) and
                (BotUpdateInbox.id greater offset.coerceAtLeast(0L))
        }
        .orderBy(BotUpdateInbox.id to org.jetbrains.exposed.sql.SortOrder.ASC)
        .limit(limit.coerceIn(1, 100))
        .map { it[BotUpdateInbox.id] to it[BotUpdateInbox.updateJson] }
}

fun BotRepository.deleteUpdates(botId: String, upToId: Long): Int = transaction {
    val now = System.currentTimeMillis()
    val bot = BotApps.selectAll()
        .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
        .firstOrNull() ?: return@transaction 0
    if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction 0
    BotUpdateInbox.deleteWhere {
        (BotUpdateInbox.botId eq botId) and (BotUpdateInbox.id lessEq upToId)
    }
}
