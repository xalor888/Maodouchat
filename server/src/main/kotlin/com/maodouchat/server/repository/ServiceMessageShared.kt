package com.maodouchat.server.repository

import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.model.MessageReactionResponse
import com.maodouchat.server.model.MessageResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

internal fun ServiceMessageRepository.reactionsInTx(messageId: String): List<MessageReactionResponse> =
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

internal fun ServiceMessageRepository.toMessageResponse(row: ResultRow): MessageResponse = MessageResponse(
    id = row[ServiceMessages.id],
    chatId = row[ServiceMessages.chatId],
    senderId = row[ServiceMessages.senderId],
    content = row[ServiceMessages.content],
    type = row[ServiceMessages.type],
    timestamp = row[ServiceMessages.timestamp],
    status = "SENT",
    editedAt = row[ServiceMessages.editedAt],
    reactions = reactionsInTx(row[ServiceMessages.id]),
)
