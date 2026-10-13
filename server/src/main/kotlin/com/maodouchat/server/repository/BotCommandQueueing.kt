package com.maodouchat.server.repository

// bot 命令入队簇：回调鉴权入队 / 用户命令入站（原 BotCommandDelivery 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Validate callback ownership/membership and enqueue it atomically with the audit row. */
fun BotRepository.enqueueCallbackIfAuthorized(
    chatId: String,
    userId: String,
    botId: String,
    messageId: String,
    callbackData: String,
    updateJson: String
): Boolean = transaction {
    if (!isBoundedId(chatId) || !isBoundedId(userId) || !isBoundedId(botId) || !isBoundedId(messageId)) {
        return@transaction false
    }
    if (callbackData.isBlank() || callbackData.length > MAX_CALLBACK_DATA_LENGTH) return@transaction false
    // 8.48：超限拒写，避免 take 截断产生损坏 JSON 行
    if (updateJson.length > MAX_UPDATE_JSON_CHARS) return@transaction false
    val now = System.currentTimeMillis()
    // Bot deletion uses bot -> chat; lock in the same order before writing bot-owned rows.
    val botRow = BotApps.selectAll()
        .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
        .forUpdate()
        .firstOrNull() ?: return@transaction false
    val owner = Users.selectAll().where { Users.id eq botRow[BotApps.ownerUserId] }.firstOrNull()
    if (owner == null || owner[Users.deletedAt] != null ||
        owner[Users.suspendedUntil] > now ||
        owner[Users.messageRestrictedUntil] > now ||
        owner[Users.postRestrictedUntil] > now
    ) return@transaction false
    Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction false
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    if (participants.none { it[ChatParticipants.userId] == userId } ||
        participants.none { it[ChatParticipants.userId] == botId }
    ) return@transaction false
    val message = ServiceMessages.selectAll().where {
        (ServiceMessages.id eq messageId) and ServiceMessages.deletedAt.isNull()
    }.firstOrNull()
        ?: return@transaction false
    if (message[ServiceMessages.chatId] != chatId || message[ServiceMessages.senderId] != botId) return@transaction false
    if (!containsCallbackData(message[ServiceMessages.content], callbackData)) return@transaction false
    evictOldestInboxLocked(botId)
    BotUpdateInbox.insert {
        it[BotUpdateInbox.botId] = botId
        it[BotUpdateInbox.updateJson] = updateJson
        it[createdAt] = now
    }
    BotCommandLogs.insert {
        it[id] = "bcl_" + UUID.randomUUID().toString().replace("-", "").take(16)
        it[BotCommandLogs.botId] = botId
        it[BotCommandLogs.chatId] = chatId
        it[BotCommandLogs.userId] = userId
        it[BotCommandLogs.command] = "callback:" + callbackData.take(40)
        it[createdAt] = now
    }
    true
}


fun BotRepository.enqueueUserCommand(
    chatId: String,
    userId: String,
    text: String,
    botIdHint: String? = null
): List<Pair<String, String>> = transaction {
    val cleaned = com.maodouchat.server.bot.BotCommandPolicy.sanitizeInboxText(text)
        ?: return@transaction emptyList()
    val now = System.currentTimeMillis()
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction emptyList()
    if (chat[Chats.chatType] == ChatType.SECRET) return@transaction emptyList()
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    if (participants.none { it[ChatParticipants.userId] == userId }) return@transaction emptyList()
    val isGroup = chat[Chats.isGroup]
    val botIds = participants.map { it[ChatParticipants.userId] }.filter { it.startsWith("bot_") }
    if (botIds.isEmpty()) return@transaction emptyList()
    val isDirectWithBot = !isGroup && botIds.size == 1 && participants.size == 2
    if (!com.maodouchat.server.bot.BotCommandPolicy.shouldAcceptInbox(cleaned, isDirectWithBot)) {
        return@transaction emptyList()
    }
    val slash = com.maodouchat.server.bot.BotCommandPolicy.parseSlash(cleaned)
    val mention = com.maodouchat.server.bot.BotCommandPolicy.parseMention(cleaned)
    val targetedUsername = slash?.targetUsername ?: mention?.username
    val targeted = when {
        !botIdHint.isNullOrBlank() -> botIds.filter { it == botIdHint }
        targetedUsername != null -> {
            val match = BotApps.selectAll()
                .where { (BotApps.username eq targetedUsername) and (BotApps.id inList botIds) }
                .map { it[BotApps.id] }
            if (match.isEmpty()) return@transaction emptyList()
            match
        }
        else -> botIds
    }
    if (targeted.isEmpty()) return@transaction emptyList()
    val rows = BotApps.selectAll()
        .where { (BotApps.id inList targeted) and (BotApps.enabled eq true) }
        .toList()
    val delivered = ArrayList<Pair<String, String>>()
    for (row in rows) {
        val botId = row[BotApps.id]
        if (!isOwnerDeliverable(row[BotApps.ownerUserId], now)) continue
        val commandName = slash?.command ?: mention?.username ?: "message"
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("update_type", kotlinx.serialization.json.JsonPrimitive("message"))
            put("event", kotlinx.serialization.json.JsonPrimitive("user_command"))
            put("chatId", kotlinx.serialization.json.JsonPrimitive(chatId))
            put("senderId", kotlinx.serialization.json.JsonPrimitive(userId))
            put("text", kotlinx.serialization.json.JsonPrimitive(cleaned.take(500)))
            put("type", kotlinx.serialization.json.JsonPrimitive("TEXT"))
            if (slash != null) {
                put("command", kotlinx.serialization.json.JsonPrimitive(slash.command))
                put("arguments", kotlinx.serialization.json.JsonPrimitive(slash.arguments))
            }
            put("ts", kotlinx.serialization.json.JsonPrimitive(now))
        }.toString()
        if (payload.length > MAX_UPDATE_JSON_CHARS) continue
        evictOldestInboxLocked(botId)
        BotUpdateInbox.insert {
            it[BotUpdateInbox.botId] = botId
            it[BotUpdateInbox.updateJson] = payload
            it[createdAt] = now
        }
        evictOldestCommandLogsLocked(botId)
        BotCommandLogs.insert {
            it[id] = "bcl_" + UUID.randomUUID().toString().replace("-", "").take(16)
            it[BotCommandLogs.botId] = botId
            it[BotCommandLogs.chatId] = chatId
            it[BotCommandLogs.userId] = userId
            it[BotCommandLogs.command] = "/$commandName".take(120)
            it[createdAt] = now
        }
        delivered += botId to payload
    }
    delivered
}
