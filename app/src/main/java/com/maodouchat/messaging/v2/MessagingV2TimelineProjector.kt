package com.maodouchat.messaging.v2

import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.SessionCipherOccupancy
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneKind
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AppNotifier
import com.maodouchat.attachment.AttachmentTransferCoordinator

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
            projectEvent(envelope, it)
            return
        }

        val owner = ownerUserId()
        val payload = ContentPayloadCodec.decode(content)
        val projected = Message(
            id = envelope.messageId,
            chatId = envelope.conversationId,
            senderId = envelope.senderUserId,
            content = projectContent(payload),
            type = payload.type,
            timestamp = envelope.clientTimestamp,
            status = if (envelope.senderUserId == owner) MessageStatus.SENT else MessageStatus.DELIVERED,
            meta = projectMetadata(payload),
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

    private fun projectContent(payload: ContentPayload): String {
        if (payload.type !in ATTACHMENT_TYPES) return payload.body
        val reference = MediaCache.decodeEncryptedAttachmentReference(payload.body) ?: return payload.body
        return MediaCache.attachmentUri(reference.attachmentId)
    }

    private fun projectMetadata(payload: ContentPayload): MessageMeta {
        if (payload.type !in ATTACHMENT_TYPES) return payload.metadata
        val reference = MediaCache.decodeEncryptedAttachmentReference(payload.body) ?: return payload.metadata
        return payload.metadata.copy(
            fileName = reference.fileName,
            fileMimeType = reference.mimeType,
            fileSizeBytes = reference.plainSize,
            attachmentId = reference.attachmentId,
            attachmentKeyBase64 = reference.keyBase64,
            attachmentIvBase64 = reference.ivBase64,
            attachmentCipherSha256 = reference.cipherSha256,
            attachmentPlainSha256 = reference.plainSha256,
            attachmentCipherSize = reference.cipherSize,
            voiceDurationMs = reference.durationMs,
        )
    }

    private suspend fun projectEvent(envelope: MessagingV2InboxEntity, event: MessagingV2Event) {
        val owner = ownerUserId()
        val existing = messageStore.getMessageById(event.targetMessageId)
            ?: if (event.action in TERMINAL_ACTIONS &&
                app.database.messagingV2Dao().isMessageTerminal(owner, event.targetMessageId)
            ) {
                cleanupTerminalArtifacts(
                    conversationId = envelope.conversationId,
                    messageId = event.targetMessageId,
                    ownerUserId = owner,
                )
                return
            } else if (event.action in MISSING_TARGET_NO_OP_ACTIONS) return
            else error("messaging_v2_event_target_missing:${event.targetMessageId}")
        if (
            !MessagingV2MutationAuthority.canApply(
                action = event.action,
                targetSenderUserId = existing.senderId,
                envelopeSenderUserId = envelope.senderUserId,
                envelopeSenderDeviceId = envelope.senderDeviceId,
                envelopeKind = envelope.kind,
            )
        ) return

        when (event.action) {
            MessagingV2EventAction.EDIT -> applyEdit(existing, event, envelope.serverTimestamp)?.let {
                onAuthoritativeMutation(
                    MessagingV2AuthoritativeMutation(
                        conversationId = existing.chatId,
                        messageId = existing.id,
                        kind = MessageMutationKind.EDIT,
                        message = it,
                    ),
                )
            }
            MessagingV2EventAction.REVOKE -> app.database.withTransaction {
                persistTerminalTombstone(owner, existing, MessageMutationTombstoneKind.REVOKE, envelope.serverTimestamp)
                applyRevoke(existing, event, envelope.serverTimestamp)
            }?.let {
                cleanupTerminalArtifacts(existing.chatId, existing.id, owner)
                onAuthoritativeMutation(
                    MessagingV2AuthoritativeMutation(
                        conversationId = existing.chatId,
                        messageId = existing.id,
                        kind = MessageMutationKind.REVOKE,
                        message = it,
                    ),
                )
            }
            MessagingV2EventAction.DELETE -> {
                app.database.withTransaction {
                    persistTerminalTombstone(
                        ownerUserId = owner,
                        message = existing,
                        kind = MessageMutationTombstoneKind.DELETE,
                        terminalAt = envelope.serverTimestamp,
                    )
                    messageStore.deleteMessage(existing.id)
                    app.database.messageSearchDao().deleteDocument(existing.id)
                }
                cleanupTerminalArtifacts(existing.chatId, existing.id, owner)
                MaodouchatApp.emitChatListPreviewRefresh(existing.chatId)
                onAuthoritativeMutation(
                    MessagingV2AuthoritativeMutation(
                        conversationId = existing.chatId,
                        messageId = existing.id,
                        kind = MessageMutationKind.DELETE,
                    ),
                )
            }
            MessagingV2EventAction.REACTION_SET -> applyReactionSet(existing, envelope, event)
            MessagingV2EventAction.REACTION_SNAPSHOT -> applyReactionSnapshot(existing, envelope, event)
            MessagingV2EventAction.DELIVERY_RECEIPT -> applyDeliveryReceipt(owner, existing, envelope)
            MessagingV2EventAction.READ_RECEIPT -> applyReadReceipt(owner, existing, envelope, event)
        }
    }

    private suspend fun applyEdit(
        existing: Message,
        event: MessagingV2Event,
        serverTimestamp: Long,
    ): Message? {
        val body = event.content ?: return null
        val editedAt = event.editedAt ?: serverTimestamp
        val applied = messageStore.applyEditedMessage(
            existing.copy(content = body, editedAt = editedAt),
        ) ?: return null
        MaodouchatApp.emitChatListPreviewRefresh(existing.chatId)
        return applied
    }

    private suspend fun applyRevoke(
        existing: Message,
        event: MessagingV2Event,
        serverTimestamp: Long,
    ): Message? {
        val applied = app.database.withTransaction {
            val updated = messageStore.applyRevokedMessage(
                existing.copy(
                    type = MessageType.REVOKED,
                    content = event.content ?: "Message revoked",
                    editedAt = event.editedAt ?: serverTimestamp,
                ),
            ) ?: return@withTransaction null
            app.database.messageSearchDao().deleteDocument(existing.id)
            updated
        } ?: return null
        MaodouchatApp.emitChatListPreviewRefresh(existing.chatId)
        return applied
    }

    private suspend fun persistTerminalTombstone(
        ownerUserId: String,
        message: Message,
        kind: String,
        terminalAt: Long,
    ) {
        app.database.messagingV2Dao().upsertMessageTombstone(
            MessageMutationTombstoneEntity(
                ownerUserId = ownerUserId,
                messageId = message.id,
                conversationId = message.chatId,
                kind = kind,
                terminalAt = terminalAt,
            ),
        )
    }

    private suspend fun cleanupTerminalArtifacts(
        conversationId: String,
        messageId: String,
        ownerUserId: String,
    ) {
        var firstFailure: Exception? = null
        suspend fun step(operation: suspend () -> Unit) {
            try {
                operation()
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
            }
        }
        step { AttachmentTransferCoordinator.discardTerminal(app, messageId, ownerUserId) }
        step { MediaCache.deleteCachedMediaForMessage(app, messageId) }
        step { app.database.messageSearchDao().deleteDocument(messageId) }
        step {
            if (app.notificationCenter.removeMessageReferences(messageId)) {
                AppNotifier.cancelMessage(app, conversationId)
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun applyReactionSet(
        existing: Message,
        envelope: MessagingV2InboxEntity,
        event: MessagingV2Event,
    ) {
        if (
            messageStore.mutateMessageReactions(existing.id) { reactions ->
                ReactionMutationPolicy.apply(
                    existing = reactions,
                    actorUserId = envelope.senderUserId,
                    emoji = event.reactionEmoji,
                    reactedAt = envelope.serverTimestamp,
                )
            } != null
        ) {
            MaodouchatApp.emitChatListPreviewRefresh(existing.chatId)
        }
    }

    private suspend fun applyReactionSnapshot(
        existing: Message,
        envelope: MessagingV2InboxEntity,
        event: MessagingV2Event,
    ) {
        if (
            messageStore.mutateMessageReactions(existing.id) { reactions ->
                ReactionMutationPolicy.applyLegacySnapshot(
                    existing = reactions,
                    actorUserId = envelope.senderUserId,
                    snapshot = event.reactions,
                    fallbackReactedAt = envelope.serverTimestamp,
                )
            } != null
        ) {
            MaodouchatApp.emitChatListPreviewRefresh(existing.chatId)
        }
    }

    private suspend fun applyDeliveryReceipt(
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

    private suspend fun applyReadReceipt(
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

    private suspend fun persistReceipt(
        ownerUserId: String,
        message: Message,
        recipientUserId: String,
        deliveredAt: Long?,
        readAt: Long? = null,
    ) {
        val dao = app.database.messagingV2Dao()
        val previous = dao.getReceipt(ownerUserId, message.id, recipientUserId)
        dao.upsertReceipt(
            MessagingV2ReceiptEntity(
                ownerUserId = ownerUserId,
                messageId = message.id,
                conversationId = message.chatId,
                recipientUserId = recipientUserId,
                deliveredAt = maxOf(previous?.deliveredAt ?: 0L, deliveredAt ?: 0L).takeIf { it > 0L },
                readAt = maxOf(previous?.readAt ?: 0L, readAt ?: 0L).takeIf { it > 0L },
                updatedAt = clock(),
            ),
        )
    }

    private companion object {
        val TERMINAL_ACTIONS = setOf(
            MessagingV2EventAction.DELETE,
            MessagingV2EventAction.REVOKE,
        )
        const val TYPE_SENDER_KEY_REQUEST = "SENDER_KEY_REQUEST"
        const val ATTRIBUTE_REQUESTED_SENDER = "requestedSenderUserId"
        val ATTACHMENT_TYPES = setOf(
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.VIDEO,
            MessageType.VOICE,
            MessageType.FILE,
        )
        val MISSING_TARGET_NO_OP_ACTIONS = setOf(
            MessagingV2EventAction.DELETE,
            MessagingV2EventAction.DELIVERY_RECEIPT,
            MessagingV2EventAction.READ_RECEIPT,
        )
    }
}
