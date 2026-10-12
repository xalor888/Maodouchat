package com.maodouchat.server.repository

import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.model.MessageReactionResponse
import com.maodouchat.server.model.MessageResponse

internal fun ServiceMessageRepository.reactionsInTx(messageId: String): List<MessageReactionResponse> =
internal fun org.jetbrains.exposed.sql.ResultRow.toResponse() = MessageResponse(
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
