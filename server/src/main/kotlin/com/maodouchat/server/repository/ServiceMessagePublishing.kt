package com.maodouchat.server.repository

// 服务消息发布簇：insert/publish 与事务内写入（原 ServiceMessageWrites 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.MessageResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Legacy content-only adapter for routes that have not yet switched to
 * [publish]. New server-authored messages must use [publish] so plaintext
 * storage and V2 mailbox rows commit together.
 */
fun ServiceMessageRepository.insert(
    id: String,
    chatId: String,
    botUserId: String,
    content: String,
    timestamp: Long,
    type: String = "TEXT",
): Boolean = transaction {
    val bot = BotApps.selectAll().where {
        (BotApps.id eq botUserId) and (BotApps.enabled eq true)
    }.forUpdate().firstOrNull() ?: return@transaction false
    if (!BotRepository.isOwnerDeliverable(bot[BotApps.ownerUserId], System.currentTimeMillis())) {
        return@transaction false
    }
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction false
    if (chat[Chats.chatType] == ChatType.SECRET) return@transaction false
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq botUserId)
    }.forUpdate().firstOrNull() ?: return@transaction false
    val normalizedType = type.take(20).ifBlank { "TEXT" }
    if (normalizedType in NON_STORABLE_TYPES) return@transaction false
    if (ServiceMessages.selectAll().where { ServiceMessages.id eq id }.firstOrNull() != null) {
        return@transaction false
    }
    val cleanContent = stripInlineMetaPreservingTrailing(content).take(MAX_CONTENT_LENGTH)
    ServiceMessages.insert {
        it[ServiceMessages.id] = id
        it[ServiceMessages.chatId] = chatId
        it[ServiceMessages.senderId] = botUserId
        it[ServiceMessages.content] = cleanContent
        it[ServiceMessages.type] = normalizedType
        it[ServiceMessages.timestamp] = timestamp
        it[ServiceMessages.editedAt] = null
        it[ServiceMessages.deletedAt] = null
    }
    Chats.update({ Chats.id eq chatId }) {
        it[lastMessage] = cleanContent.take(200)
        it[lastMessageType] = normalizedType
        it[lastMessageTime] = timestamp
    }
    true
}


/**
 * Atomically writes the server-visible service message and its V2 mailbox
 * rows. The recipient set is intentionally supplied by the caller because
 * block policy remains route-specific.
 */
fun ServiceMessageRepository.publish(
    id: String,
    chatId: String,
    botUserId: String,
    content: String,
    timestamp: Long,
    type: String = "TEXT",
    recipientUserIds: Set<String>,
): PublishResult = transaction {
    val message = insertServiceMessageInTransaction(id, chatId, botUserId, content, timestamp, type)
        ?: return@transaction Rejected
    val mailbox = messagingV2Repository.enqueueServiceMessageInTransaction(message, recipientUserIds)
    Published(message, mailbox)
}


private fun ServiceMessageRepository.insertServiceMessageInTransaction(
    id: String,
    chatId: String,
    botUserId: String,
    content: String,
    timestamp: Long,
    type: String,
): MessageResponse? {
    val bot = BotApps.selectAll().where {
        (BotApps.id eq botUserId) and (BotApps.enabled eq true)
    }.forUpdate().firstOrNull() ?: return null
    if (!BotRepository.isOwnerDeliverable(bot[BotApps.ownerUserId], System.currentTimeMillis())) return null
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull() ?: return null
    if (chat[Chats.chatType] == ChatType.SECRET) return null
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq botUserId)
    }.forUpdate().firstOrNull() ?: return null
    val normalizedType = type.take(20).ifBlank { "TEXT" }
    if (normalizedType in NON_STORABLE_TYPES) return null
    if (ServiceMessages.selectAll().where { ServiceMessages.id eq id }.firstOrNull() != null) return null
    val cleanContent = stripInlineMetaPreservingTrailing(content).take(MAX_CONTENT_LENGTH)
    ServiceMessages.insert {
        it[ServiceMessages.id] = id
        it[ServiceMessages.chatId] = chatId
        it[ServiceMessages.senderId] = botUserId
        it[ServiceMessages.content] = cleanContent
        it[ServiceMessages.type] = normalizedType
        it[ServiceMessages.timestamp] = timestamp
        it[ServiceMessages.editedAt] = null
        it[ServiceMessages.deletedAt] = null
    }
    Chats.update({ Chats.id eq chatId }) {
        it[lastMessage] = cleanContent.take(200)
        it[lastMessageType] = normalizedType
        it[lastMessageTime] = timestamp
    }
    return MessageResponse(
        id = id,
        chatId = chatId,
        senderId = botUserId,
        content = cleanContent,
        type = normalizedType,
        timestamp = timestamp,
        status = "SENT",
    )
}
