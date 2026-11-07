package com.maodouchat.core.model

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
