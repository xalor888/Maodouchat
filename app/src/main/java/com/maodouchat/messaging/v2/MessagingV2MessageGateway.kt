package com.maodouchat.messaging.v2

import androidx.room.withTransaction
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore

/**
 * Application boundary for user-visible v2 messages.
 *
 * A message becomes durable locally before it is handed to the encrypted
 * outbox. Screens may still decide optimistic UI and error presentation, but
 * they do not reimplement the persistence/enqueue ordering.
 */
class MessagingV2MessageGateway(
    private val database: AppDatabase,
    private val messageStore: LocalMessageStore,
    private val outbox: MessagingV2Outbox,
    private val indexMessage: suspend (Message) -> Unit = {},
) {
    suspend fun stageAndEnqueue(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): Boolean {
        val owner = outbox.currentOwnerUserId()
        val staged = database.withTransaction {
            if (database.messagingV2Dao().isMessageTerminal(owner, message.id)) {
                false
            } else {
                messageStore.insertMessage(message)
                outbox.enqueueContentInCurrentTransaction(
                    conversationId = message.chatId,
                    body = body,
                    type = type,
                    groupRevision = groupRevision,
                    messageId = message.id,
                )
                true
            }
        }
        if (!staged) return false
        runCatching { indexMessage(message) }
        outbox.wakeAfterCommit()
        return true
    }

    suspend fun retry(
        message: Message,
        groupRevision: Long? = null,
        body: String = message.content,
        type: MessageType = message.type,
    ): Boolean {
        val owner = outbox.currentOwnerUserId()
        val staged = database.withTransaction {
            if (database.messagingV2Dao().isMessageTerminal(owner, message.id)) {
                false
            } else {
                messageStore.insertMessage(message)
                outbox.retryContentInCurrentTransaction(
                    conversationId = message.chatId,
                    body = body,
                    type = type,
                    groupRevision = groupRevision,
                    messageId = message.id,
                )
                true
            }
        }
        if (staged) outbox.wakeAfterCommit()
        return staged
    }
}
