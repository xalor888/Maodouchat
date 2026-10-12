package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.MessageReactionResponse
import com.maodouchat.server.model.MessageResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun ServiceMessageRepository.getById(messageId: String): MessageResponse? = transaction {
    ServiceMessages.selectAll().where {
        (ServiceMessages.id eq messageId) and ServiceMessages.deletedAt.isNull()
    }.firstOrNull()?.toResponse()
}
fun ServiceMessageRepository.list(chatId: String, limit: Int, viewerBotId: String): List<MessageResponse> = transaction {
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
fun ServiceMessageRepository.metadata(messageId: String): ServiceMessageMetadata? = transaction {
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
fun ServiceMessageRepository.reactions(messageId: String): List<MessageReactionResponse> = transaction {
    reactionsInTx(messageId)
}
