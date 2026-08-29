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
import java.sql.SQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Dedicated plaintext store for server-authored bot messages. */
class ServiceMessageRepository {

    fun insert(
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

    fun getById(messageId: String): MessageResponse? = transaction {
        ServiceMessages.selectAll().where {
            (ServiceMessages.id eq messageId) and ServiceMessages.deletedAt.isNull()
        }.firstOrNull()?.toResponse()
    }

    fun list(chatId: String, limit: Int, viewerBotId: String): List<MessageResponse> = transaction {
        val isMember = ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq viewerBotId)
        }.firstOrNull() != null
        if (!isMember) return@transaction emptyList()
        ServiceMessages.selectAll().where {
            (ServiceMessages.chatId eq chatId) and ServiceMessages.deletedAt.isNull()
        }.orderBy(ServiceMessages.timestamp to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .map { it.toResponse() }
            .reversed()
    }

    fun editOwn(messageId: String, botUserId: String, newContent: String, editedAt: Long): MessageResponse? =
        transaction {
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
            val clean = stripInlineMetaPreservingTrailing(newContent).take(MAX_CONTENT_LENGTH)
            ServiceMessages.update({ ServiceMessages.id eq messageId }) {
                it[content] = clean
                it[ServiceMessages.editedAt] = editedAt
            }
            message.toResponse().copy(content = clean, editedAt = editedAt)
        }

    fun deleteOwn(messageId: String, botUserId: String): MessageResponse? = transaction {
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

    fun metadata(messageId: String): ServiceMessageMetadata? = transaction {
        val transport = MessagingV2Messages.selectAll().where {
            (MessagingV2Messages.id eq messageId) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }.firstOrNull() ?: return@transaction null
        val service = ServiceMessages.selectAll().where {
            (ServiceMessages.id eq messageId) and ServiceMessages.deletedAt.isNull()
        }.firstOrNull()
        ServiceMessageMetadata(
            messageId = messageId,
            chatId = transport[MessagingV2Messages.conversationId],
            senderId = transport[MessagingV2Messages.senderUserId],
            serviceMessage = service?.toResponse(),
        )
    }

    fun setReaction(messageId: String, botUserId: String, emoji: String): List<MessageReactionResponse>? = try {
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

    fun reactions(messageId: String): List<MessageReactionResponse> = transaction {
        reactionsInTx(messageId)
    }

    private fun reactionsInTx(messageId: String): List<MessageReactionResponse> =
        ServiceMessageReactions.selectAll()
            .where { ServiceMessageReactions.messageId eq messageId }
            .orderBy(ServiceMessageReactions.reactedAt to SortOrder.ASC)
            .map {
                MessageReactionResponse(
                    userId = it[ServiceMessageReactions.botUserId],
                    emoji = it[ServiceMessageReactions.emoji],
                    reactedAt = it[ServiceMessageReactions.reactedAt],
                )
            }

    private fun org.jetbrains.exposed.sql.ResultRow.toResponse() = MessageResponse(
        id = this[ServiceMessages.id],
        chatId = this[ServiceMessages.chatId],
        senderId = this[ServiceMessages.senderId],
        content = this[ServiceMessages.content],
        type = this[ServiceMessages.type],
        timestamp = this[ServiceMessages.timestamp],
        status = "SENT",
        editedAt = this[ServiceMessages.editedAt],
        reactions = reactionsInTx(this[ServiceMessages.id]),
    )

    data class ServiceMessageMetadata(
        val messageId: String,
        val chatId: String,
        val senderId: String,
        val serviceMessage: MessageResponse?,
    )

    companion object {
        private const val MAX_CONTENT_LENGTH = 8_000
        private val NON_STORABLE_TYPES = setOf("SK_DIST", "REVOKED")

        private fun isUniqueViolation(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is SQLException && current.sqlState == "23505") return true
                val message = current.message.orEmpty().lowercase()
                if (message.contains("unique") || message.contains("duplicate key")) return true
                current = current.cause
            }
            return false
        }
    }
}
