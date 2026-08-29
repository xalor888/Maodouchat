package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException

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

/**
 * Owns the durable-send ordering shared by composer text and inline content.
 * UI layers only project [OutgoingMessageResult] into screen state.
 */
class OutgoingMessageCoordinator(
    private val resolveConversation: suspend () -> OutgoingConversationContext,
    private val stageDurableMessage: suspend (
        message: Message,
        groupRevision: Long?,
        body: String,
        type: MessageType,
    ) -> Unit,
    private val retryDurableMessage: suspend (
        message: Message,
        groupRevision: Long?,
        body: String,
        type: MessageType,
    ) -> Unit = stageDurableMessage,
    private val persistFailedMessage: suspend (Message) -> Unit,
    private val isOwnerSessionCurrent: (String) -> Boolean,
) {
    suspend fun enqueue(
        command: OutgoingMessageCommand,
        onDurableCommit: () -> Unit = {},
        onDurableFailure: () -> Unit = {},
        afterDurableCommit: suspend (OutgoingConversationContext, Message) -> Unit = { _, _ -> },
    ): OutgoingMessageResult = execute(
        command = command,
        durableOperation = stageDurableMessage,
        onDurableCommit = onDurableCommit,
        onDurableFailure = onDurableFailure,
        afterDurableCommit = afterDurableCommit,
    )

    suspend fun retry(command: OutgoingMessageCommand): OutgoingMessageResult = execute(
        command = command,
        durableOperation = retryDurableMessage,
    )

    private suspend fun execute(
        command: OutgoingMessageCommand,
        durableOperation: suspend (Message, Long?, String, MessageType) -> Unit,
        onDurableCommit: () -> Unit = {},
        onDurableFailure: () -> Unit = {},
        afterDurableCommit: suspend (OutgoingConversationContext, Message) -> Unit = { _, _ -> },
    ): OutgoingMessageResult {
        var durable = command.optimisticMessage
        var committed = false
        try {
            val conversation = resolveConversation()
            durable = command.optimisticMessage.copy(chatId = conversation.conversationId)
            durableOperation(durable, conversation.groupRevision, command.body, command.type)
            committed = true
            runCatching(onDurableCommit)
            afterDurableCommit(conversation, durable)
            if (!isOwnerSessionCurrent(command.ownerUserId)) {
                throw CancellationException("outgoing_message_session_changed")
            }
            return OutgoingMessageResult.Staged(durable, conversation)
        } catch (cancelled: CancellationException) {
            if (!committed) runCatching(onDurableFailure)
            throw cancelled
        } catch (error: Throwable) {
            if (!committed) runCatching(onDurableFailure)
            val failed = durable.copy(status = MessageStatus.FAILED)
            if (failed.chatId.isNotBlank()) {
                runCatching { persistFailedMessage(failed) }
            }
            return OutgoingMessageResult.Failed(failed, error, committed)
        }
    }
}
