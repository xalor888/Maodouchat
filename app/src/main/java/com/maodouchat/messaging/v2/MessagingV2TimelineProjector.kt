package com.maodouchat.messaging.v2

import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.SessionCipherOccupancy
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.repository.LocalMessageStore

/** Commits decrypted v2 content into the local timeline and applies encrypted domain events. */
internal class MessagingV2TimelineProjector(
    private val app: MaodouchatApp,
    private val messageStore: LocalMessageStore,
    private val ownerUserId: () -> String,
    private val notifier: MessagingV2ArrivalNotifier,
    private val sendDeliveryReceipt: suspend (MessagingV2InboxEntity) -> Unit,
    private val onAuthoritativeMutation: suspend (MessagingV2AuthoritativeMutation) -> Unit = {},
    private val onSenderKeyRequest: suspend (conversationId: String, epoch: Long, requesterUserId: String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val receiptProjector: MessageReceiptProjector = MessageReceiptProjector(app, messageStore, clock),
    private val eventProjector: MessageEventProjector = MessageEventProjector(app, messageStore, ownerUserId, receiptProjector, onAuthoritativeMutation),
) {
    suspend fun project(envelope: MessagingV2InboxEntity, content: MessagingV2Content) {
        if (content.type == TYPE_SENDER_KEY_REQUEST) {
            val owner = ownerUserId()
            val requestedSender = content.attributes[ATTRIBUTE_REQUESTED_SENDER]
            val epoch = envelope.groupRevision ?: 0L
            if (
                owner.isNotBlank() &&
                requestedSender == owner &&
                envelope.senderUserId != owner &&
                epoch > 0L
            ) {
                onSenderKeyRequest(envelope.conversationId, epoch, envelope.senderUserId)
            }
            return
        }
        content.event?.let {
            eventProjector.projectEvent(envelope, it)
            return
        }

        val owner = ownerUserId()
        val payload = ContentPayloadCodec.decode(content)
        val projected = Message(
            id = envelope.messageId,
            chatId = envelope.conversationId,
            senderId = envelope.senderUserId,
            content = MessageContentProjector.projectContent(payload),
            type = payload.type,
            timestamp = envelope.clientTimestamp,
            status = if (envelope.senderUserId == owner) MessageStatus.SENT else MessageStatus.DELIVERED,
            meta = MessageContentProjector.projectMetadata(payload),
        )
        val arrival = MessagingV2ArrivalPolicy.evaluate(
            isNew = true,
            ownerUserId = owner,
            senderUserId = projected.senderId,
            conversationId = projected.chatId,
            messageType = projected.type,
            envelopeKind = envelope.kind,
            isConversationOccupied = SessionCipherOccupancy.isChatOccupied(projected.chatId),
            appInForeground = MaodouchatApp.appInForeground,
            activeChatId = MaodouchatApp.activeChatId,
            openChatDetailId = MaodouchatApp.openChatDetailId,
        )
        val inserted = app.database.withTransaction {
            if (
                app.database.messagingV2Dao().isMessageTerminal(owner, projected.id) ||
                app.database.messageDao().getMessageById(projected.id) != null
            ) {
                false
            } else {
                messageStore.insertMessage(projected)
                app.database.chatDao().projectMessageArrival(
                    chatId = projected.chatId,
                    content = projected.content,
                    messageType = projected.type.name,
                    timestamp = projected.timestamp,
                    unreadDelta = arrival.unreadDelta,
                )
                true
            }
        }
        if (!inserted) return

        MaodouchatApp.emitChatListPreviewRefresh(envelope.conversationId)
        if (arrival.shouldAttemptNotification) notifier.notify(projected)
        if (arrival.shouldSendDeliveryReceipt) sendDeliveryReceipt(envelope)
    }

    private companion object {
        const val TYPE_SENDER_KEY_REQUEST = "SENDER_KEY_REQUEST"
        const val ATTRIBUTE_REQUESTED_SENDER = "requestedSenderUserId"
    }
}
