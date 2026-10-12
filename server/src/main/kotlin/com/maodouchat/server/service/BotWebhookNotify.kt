package com.maodouchat.server.service

// 事件通知入口（chat 事件/直投/owner 校验）：原 BotWebhookService 成员按域拆出的扩展函数（同包调用，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import com.maodouchat.server.repository.enqueueUpdate
import com.maodouchat.server.repository.logCommand
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun BotWebhookService.notifyChatEvent(
    chatId: String,
    event: String,
    messageId: String? = null,
    senderId: String? = null,
    type: String? = null,
    textPreview: String? = null,
    sealedSender: Boolean = false
) {
    if (chatId.isBlank()) return
    data class Target(val botId: String, val url: String?, val tokenHash: String)
    // B12：同步解析目标 + 落 outbox/inbox，再异步投递——进程在 enqueue 与投递之间退出也不丢事件。
    val targets = transaction {
        val now = System.currentTimeMillis()
        val botIds = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
            .filter { it.startsWith("bot_") }
            // 8.33 修复：事件发送者（bot 自己）不得收到自身事件的 webhook 回声，否则
            // bot 发消息 → 收到 bot_message → 自动回复 → 无限回声循环
            .filter { it != senderId }
        if (botIds.isEmpty()) return@transaction emptyList()
        val rows = BotApps.selectAll()
            .where { (BotApps.id inList botIds) and (BotApps.enabled eq true) }
            .toList()
        val activeOwnerIds = rows.map { it[BotApps.ownerUserId] }.distinct()
            .filter { isBotOwnerDeliverable(it, now) }
            .toSet()
        if (activeOwnerIds.isEmpty()) return@transaction emptyList()
        rows.filter { it[BotApps.ownerUserId] in activeOwnerIds }
            .map { row ->
                val url = row[BotApps.webhookUrl]?.trim().orEmpty().ifBlank { null }
                Target(row[BotApps.id], url, row[BotApps.tokenHash])
            }
    }
    if (targets.isEmpty()) return
    val ts = System.currentTimeMillis()
    val body = buildJsonObject {
        put("event", event)
        put("chatId", chatId)
        if (messageId != null) put("messageId", messageId)
        if (senderId != null) put(
            "senderId",
            SealedSenderDelivery.webhookSenderId(senderId, sealedSender) ?: senderId
        )
        if (sealedSender) put("sealedSender", true)
        if (type != null) put("type", type)
        if (!textPreview.isNullOrBlank()) put("text", textPreview.take(500))
        val slash = textPreview?.trim().orEmpty()
        if (slash.startsWith("/")) {
            val cmd = slash.removePrefix("/")
                .substringBefore(" ")
                .substringBefore("@")
                .lowercase()
                .take(64)
            if (cmd.isNotBlank()) put("command", cmd)
        }
        put("ts", ts)
    }.toString()
    // 同步路由：无 webhook 的 bot 直接入长轮询收件箱；有 webhook 的先落 outbox，再异步投递。
    val pending = ArrayList<BotWebhookService.OutboxEntry>()
    targets.forEach { t ->
        if (t.url.isNullOrBlank()) {
            try {
                com.maodouchat.server.repository.BotRepository.enqueueUpdate(t.botId, body)
            } catch (e: Exception) {
                logger.warn("Bot inbox enqueue failed for bot {}", t.botId, e)
            }
        } else {
            val id = "wh_${UUID.randomUUID()}"
            insertOutbox(id, t.botId, t.url, t.tokenHash, body, ts)
            pending += BotWebhookService.OutboxEntry(id, t.botId, t.url, t.tokenHash, body, ts)
        }
        // Audit slash commands for developer console.
        val cmdName = runCatching {
            val slash = textPreview?.trim().orEmpty()
            if (!slash.startsWith("/")) null
            else slash.removePrefix("/").substringBefore(" ").substringBefore("@").lowercase().take(64)
        }.getOrNull()
        if (!cmdName.isNullOrBlank()) {
            try {
                com.maodouchat.server.repository.BotRepository.logCommand(
                    t.botId, chatId, senderId, "/$cmdName"
                )
            } catch (e: Exception) {
                logger.warn("Bot command audit failed for bot {}", t.botId, e)
            }
        }
    }
    // 异步投递（worker 池并行）；本 worker 投成 DEAD 时回退收件箱兜底。
    pending.forEach { entry ->
        enqueue {
            val outcome = deliverWebhook(entry.id, entry.url, entry.body, entry.ts, entry.tokenHash, entry.botId)
            if (outcome == BotWebhookService.DeliveryOutcome.DEAD) {
                try {
                    com.maodouchat.server.repository.BotRepository.enqueueUpdate(entry.botId, entry.body)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    logger.warn("Bot inbox fallback failed for bot {}", entry.botId, e)
                }
            }
        }
    }
}

/** Deliver a pre-built update JSON to one bot (inbox + optional webhook). Does not re-enqueue duplicates across chat bots. */
fun BotWebhookService.notifyBotDirect(botId: String, bodyJson: String) {
    if (botId.isBlank() || bodyJson.isBlank()) return
    val target = transaction {
        val now = System.currentTimeMillis()
        val row = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull()
        row?.takeIf { isBotOwnerDeliverable(it[BotApps.ownerUserId], now) }?.let { bot ->
            val url = bot[BotApps.webhookUrl]?.trim().orEmpty().ifBlank { null }
            Triple(bot[BotApps.id], url, bot[BotApps.tokenHash])
        }
    } ?: return
    val ts = System.currentTimeMillis()
    // Inbox already enqueued by caller; only fire webhook here if configured.
    val url = target.second
    if (url.isNullOrBlank()) return
    val id = "wh_${UUID.randomUUID()}"
    insertOutbox(id, target.first, url, target.third, bodyJson, ts)
    enqueue {
        val outcome = deliverWebhook(id, url, bodyJson, ts, target.third, target.first)
        if (outcome == BotWebhookService.DeliveryOutcome.DEAD) {
            // 8.39：不再补写收件箱——调用方（enqueueCallbackIfAuthorized）已入队，
            // 此处补投会造成同一条事件重复入 inbox（bot 用 getUpdates 拉取收到两次）。
            logger.warn(
                "webhook delivery failed for bot {}; inbox fallback already enqueued",
                target.first
            )
        }
    }
}

/** Align webhook fanout with BotRepository.authenticate: suspended/deleted/restricted owners must not keep receiving bot events. */
internal fun BotWebhookService.isBotOwnerDeliverable(ownerUserId: String, now: Long): Boolean {
    val owner = Users.selectAll().where { Users.id eq ownerUserId }.firstOrNull() ?: return false
    return owner[Users.deletedAt] == null &&
        owner[Users.suspendedUntil] <= now &&
        owner[Users.messageRestrictedUntil] <= now &&
        owner[Users.postRestrictedUntil] <= now
}
