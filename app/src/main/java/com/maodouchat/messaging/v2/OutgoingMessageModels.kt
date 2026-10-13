package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

data class OutgoingConversationContext(
    val conversationId: String,
    val groupRevision: Long?,
    val isGroup: Boolean,
    val peerUserId: String?,
)

data class OutgoingMessageCommand(
    val ownerUserId: String,
    val optimisticMessage: Message,
    val body: String,
    val type: MessageType,
)

sealed interface OutgoingMessageResult {
    data class Staged(
        val message: Message,
        val conversation: OutgoingConversationContext,
    ) : OutgoingMessageResult

    data class Failed(
        val message: Message,
        val error: Throwable,
        val durableCommitCompleted: Boolean,
    ) : OutgoingMessageResult
}
