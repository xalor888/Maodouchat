package com.maodouchat.messaging.v2

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.repository.LocalMessageStore

/** M02：RECEIPT 投影——把送达/已读回执落库并推进消息状态，与 DATA/EVENT 投影解耦。 */
internal class MessageReceiptProjector(
    private val app: MaodouchatApp,
    private val messageStore: LocalMessageStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun applyDeliveryReceipt(
        owner: String,
        existing: Message,
        envelope: MessagingV2InboxEntity,
    ) {
        if (existing.senderId != owner || envelope.senderUserId == owner) return
        persistReceipt(
            ownerUserId = owner,
            message = existing,
            recipientUserId = envelope.senderUserId,
            deliveredAt = envelope.serverTimestamp,
        )
        messageStore.updateMessageStatus(existing.id, MessageStatus.DELIVERED)
    }

    suspend fun applyReadReceipt(
        owner: String,
        existing: Message,
        envelope: MessagingV2InboxEntity,
        event: MessagingV2Event,
    ) {
        val through = event.throughMessageId ?: event.targetMessageId
        val boundary = messageStore.getMessageById(through) ?: return
        if (envelope.senderUserId == owner) {
            app.database.messageDao().markIncomingReadThrough(
                chatId = envelope.conversationId,
                ownerUserId = owner,
                throughTimestamp = boundary.timestamp,
                throughMessageId = boundary.id,
            )
            app.database.chatDao().markAllRead(envelope.conversationId)
            MaodouchatApp.emitChatRead(envelope.conversationId)
            return
        }
        if (boundary.senderId != owner) return

        val outgoing = app.database.messageDao().getOutgoingMessagesThrough(
            chatId = envelope.conversationId,
            senderId = owner,
            throughTimestamp = boundary.timestamp,
            throughMessageId = boundary.id,
        ).map { it.toDomain() }
        val isGroup = app.database.chatDao().getChatById(envelope.conversationId)?.isGroup == true
        outgoing.forEach { message ->
            persistReceipt(
                ownerUserId = owner,
                message = message,
                recipientUserId = envelope.senderUserId,
                deliveredAt = envelope.serverTimestamp,
                readAt = envelope.serverTimestamp,
            )
            messageStore.updateMessageStatus(
                message.id,
                if (isGroup) MessageStatus.DELIVERED else MessageStatus.READ,
            )
        }
    }

    suspend fun applyPlayReceipt(
        owner: String,
        existing: Message,
        envelope: MessagingV2InboxEntity,
        event: MessagingV2Event,
    ) {
        val targetMessageId = event.targetMessageId
        val target = if (existing.id == targetMessageId) existing else messageStore.getMessageById(targetMessageId) ?: return
        if (envelope.senderUserId == owner) return
        if (target.senderId != owner) return

        persistReceipt(
            ownerUserId = owner,
            message = target,
            recipientUserId = envelope.senderUserId,
            deliveredAt = envelope.serverTimestamp,
            readAt = envelope.serverTimestamp,
            playedAt = envelope.serverTimestamp,
        )
    }

    private suspend fun persistReceipt(
        ownerUserId: String,
        message: Message,
        recipientUserId: String,
        deliveredAt: Long?,
        readAt: Long? = null,
        playedAt: Long? = null,
    ) {
        val dao = app.database.messagingV2Dao()
        val previous = dao.getReceipt(ownerUserId, message.id, recipientUserId)
        val merged = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = previous,
            ownerUserId = ownerUserId,
            messageId = message.id,
            conversationId = message.chatId,
            recipientUserId = recipientUserId,
            deliveredAt = deliveredAt,
            readAt = readAt,
            playedAt = playedAt,
            now = clock(),
        )
        dao.upsertReceipt(merged)
    }
}
