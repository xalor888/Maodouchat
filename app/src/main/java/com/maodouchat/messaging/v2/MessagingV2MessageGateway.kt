package com.maodouchat.messaging.v2

import androidx.room.withTransaction
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore

sealed interface MessagingV2MessageGatewayOutcome {
    data class Staged(val message: Message) : MessagingV2MessageGatewayOutcome

    sealed interface Rejected : MessagingV2MessageGatewayOutcome {
        data class TerminalTombstone(val messageId: String) : Rejected
    }
}

/** Injectable staging boundary used by conversation commands and focused JVM tests. */
interface ConversationMessageStagingGateway {
    suspend fun stage(
        message: Message,
        payload: ContentPayload,
        groupRevision: Long? = null,
    ): MessagingV2MessageGatewayOutcome

    suspend fun retry(
        message: Message,
        payload: ContentPayload,
        groupRevision: Long? = null,
    ): MessagingV2MessageGatewayOutcome
}

/**
 * Application boundary for user-visible v2 messages.
 *
 * A message becomes durable locally before it is handed to the encrypted outbox. Screens may
 * still decide optimistic UI and error presentation, but do not reimplement persistence/enqueue
 * ordering. New callers use typed [ContentPayload] and explicit [MessagingV2MessageGatewayOutcome].
 */
class MessagingV2MessageGateway(
    private val database: AppDatabase,
    private val messageStore: LocalMessageStore,
    private val outbox: MessagingV2Outbox,
    private val indexMessage: suspend (Message) -> Unit = {},
) : ConversationMessageStagingGateway {
    override suspend fun stage(
        message: Message,
        payload: ContentPayload,
        groupRevision: Long?,
    ): MessagingV2MessageGatewayOutcome {
        val normalized = ContentPayloadCodec.normalizeLocalMessage(message)
        val owner = outbox.currentOwnerUserId()
        val staged = database.withTransaction {
            if (database.messagingV2Dao().isMessageTerminal(owner, normalized.id)) {
                false
            } else {
                messageStore.insertMessage(normalized)
                outbox.enqueueContentPayloadInCurrentTransaction(
                    conversationId = normalized.chatId,
                    payload = payload,
                    groupRevision = groupRevision,
                    messageId = normalized.id,
                )
                true
            }
        }
        if (!staged) return MessagingV2MessageGatewayOutcome.Rejected.TerminalTombstone(normalized.id)
        runCatching { indexMessage(normalized) }
        outbox.wakeAfterCommit()
        return MessagingV2MessageGatewayOutcome.Staged(normalized)
    }

    override suspend fun retry(
        message: Message,
        payload: ContentPayload,
        groupRevision: Long?,
    ): MessagingV2MessageGatewayOutcome {
        val normalized = ContentPayloadCodec.normalizeLocalMessage(message)
        val owner = outbox.currentOwnerUserId()
        val staged = database.withTransaction {
            if (database.messagingV2Dao().isMessageTerminal(owner, normalized.id)) {
                false
            } else {
                messageStore.insertMessage(normalized)
                outbox.retryContentPayloadInCurrentTransaction(
                    conversationId = normalized.chatId,
                    payload = payload,
                    groupRevision = groupRevision,
                    messageId = normalized.id,
                )
                true
            }
        }
        if (!staged) return MessagingV2MessageGatewayOutcome.Rejected.TerminalTombstone(normalized.id)
        outbox.wakeAfterCommit()
        return MessagingV2MessageGatewayOutcome.Staged(normalized)
    }

    /** Explicit-outcome API for new callers. */
    suspend fun stageAndEnqueueOutcome(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): MessagingV2MessageGatewayOutcome = stage(
        message = message,
        payload = ContentPayloadCodec.fromLegacyMessage(message, body, type),
        groupRevision = groupRevision,
    )

    /** Compatibility adapter retained while existing callers migrate to [stageAndEnqueueOutcome]. */
    suspend fun stageAndEnqueue(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): Boolean = stageAndEnqueueOutcome(message, groupRevision, body, type) is MessagingV2MessageGatewayOutcome.Staged

    /** Explicit-outcome API for new callers. */
    suspend fun retryOutcome(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): MessagingV2MessageGatewayOutcome = retry(
        message = message,
        payload = ContentPayloadCodec.fromLegacyMessage(message, body, type),
        groupRevision = groupRevision,
    )

    /** Compatibility adapter retained while existing callers migrate to [retryOutcome]. */
    suspend fun retry(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): Boolean = retryOutcome(message, groupRevision, body, type) is MessagingV2MessageGatewayOutcome.Staged
}
