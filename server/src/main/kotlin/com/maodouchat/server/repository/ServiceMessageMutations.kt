package com.maodouchat.server.repository

// 服务消息自有变更簇：编辑/删除/表情回应（原 ServiceMessageWrites 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
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

fun ServiceMessageRepository.editOwn(messageId: String, botUserId: String, newContent: String, editedAt: Long): MessageResponse? =
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
        toMessageResponse(message).copy(content = clean, editedAt = editedAt)
    }


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
    toMessageResponse(message)
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
