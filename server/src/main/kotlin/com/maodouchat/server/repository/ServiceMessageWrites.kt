package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.MessageReactionResponse
import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
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
        ?: return@transaction PublishResult.Rejected
    val mailbox = messagingV2Repository.enqueueServiceMessageInTransaction(message, recipientUserIds)
    PublishResult.Published(message, mailbox)
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
fun ServiceMessageRepository.editOwn(messageId: String, botUserId: String, newContent: String, editedAt: Long): MessageResponse? =
fun ServiceMessageRepository.deleteOwn(messageId: String, botUserId: String): MessageResponse? = transaction {
    if (!BotRepository.isBotDeliverableInTx(botUserId, System.currentTimeMillis())) {
        return@transaction null
    }
    val message = ServiceMessages.selectAll().where {
        (ServiceMessages.id eq messageId) and ServiceMessages.deletedAt.isNull()
    }.forUpdate().firstOrNull() ?: return@transaction null
    if (message[ServiceMessages.senderId] != botUserId) return@transaction null
    val chatId = message[ServiceMessages.chatId]
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq botUserId)
    }.forUpdate().firstOrNull() ?: return@transaction null
    ServiceMessages.update({ ServiceMessages.id eq messageId }) {
        it[deletedAt] = System.currentTimeMillis()
        it[content] = ""
    }
    val attachmentIds = EncryptedAttachments.selectAll()
        .where { EncryptedAttachments.messageId eq messageId }
        .map { it[EncryptedAttachments.id] }
    if (attachmentIds.isNotEmpty()) {
        EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
    }
    StarMessages.deleteWhere { StarMessages.messageId eq messageId }
    PinnedMessages.deleteWhere { PinnedMessages.messageId eq messageId }
    ServiceMessageReactions.deleteWhere { ServiceMessageReactions.messageId eq messageId }
    message.toResponse()
}
fun ServiceMessageRepository.setReaction(messageId: String, botUserId: String, emoji: String): List<MessageReactionResponse>? = try {
    transaction {
        if (!BotRepository.isBotDeliverableInTx(botUserId, System.currentTimeMillis())) {
            return@transaction null
        }
        val message = MessagingV2Messages.selectAll().where {
            (MessagingV2Messages.id eq messageId) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }.firstOrNull() ?: return@transaction null
        val chatId = message[MessagingV2Messages.conversationId]
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq botUserId)
        }.forUpdate().firstOrNull() ?: return@transaction null
        val existing = ServiceMessageReactions.selectAll().where {
            (ServiceMessageReactions.messageId eq messageId) and
                (ServiceMessageReactions.botUserId eq botUserId)
        }.forUpdate().firstOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            ServiceMessageReactions.insert {
                it[ServiceMessageReactions.messageId] = messageId
                it[ServiceMessageReactions.botUserId] = botUserId
                it[ServiceMessageReactions.emoji] = emoji
                it[reactedAt] = now
            }
        } else {
            ServiceMessageReactions.update({
                (ServiceMessageReactions.messageId eq messageId) and
                    (ServiceMessageReactions.botUserId eq botUserId)
            }) {
                it[ServiceMessageReactions.emoji] = emoji
                it[reactedAt] = now
            }
        }
        reactionsInTx(messageId)
    }
} catch (error: Exception) {
    if (!isUniqueViolation(error)) throw error
    transaction { reactionsInTx(messageId) }
}
