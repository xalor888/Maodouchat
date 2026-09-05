package com.maodouchat.messaging.v2

import com.maodouchat.notification.MessageNotificationService
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneKind
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore

import com.maodouchat.util.MediaCache
import com.maodouchat.util.ScheduledMessageScheduler

/** M02：EVENT 投影——把加密域事件（编辑/撤回/删除/回应/回执）落到本地时间线，与 DATA 投影解耦。 */
internal class MessageEventProjector(
    private val app: MaodouchatApp,
    private val messageStore: LocalMessageStore,
    private val ownerUserId: () -> String,
    private val receiptProjector: MessageReceiptProjector,
    private val onAuthoritativeMutation: suspend (MessagingV2AuthoritativeMutation) -> Unit = {},
) {
    suspend fun projectEvent(envelope: MessagingV2InboxEntity, event: MessagingV2Event) {
        val owner = ownerUserId()
        if (app.database.messagingV2Dao().isMessageTerminal(owner, event.targetMessageId)) {
            cleanupTerminalArtifacts(
                conversationId = envelope.conversationId,
                messageId = event.targetMessageId,
                ownerUserId = owner,
            )
            return
        }

        val existing = messageStore.getMessageById(event.targetMessageId)
        if (existing == null) {
            if (event.action in TERMINAL_ACTIONS) {
                val kind = when (event.action) {
                    MessagingV2EventAction.REVOKE -> MessageMutationTombstoneKind.REVOKE
                    else -> MessageMutationTombstoneKind.DELETE
                }
                app.database.withTransaction {
                    app.database.messagingV2Dao().upsertMessageTombstone(
                        MessageMutationTombstoneEntity(
                            ownerUserId = owner,
                            messageId = event.targetMessageId,
                            conversationId = envelope.conversationId,
                            kind = kind,
                            terminalAt = envelope.serverTimestamp,
                        ),
                    )
                }
                cleanupTerminalArtifacts(
                    conversationId = envelope.conversationId,
                    messageId = event.targetMessageId,
                    ownerUserId = owner,
                )
                if (event.action == MessagingV2EventAction.DELETE) {
                    MaodouchatApp.emitChatListPreviewRefresh(envelope.conversationId)
                }
                onAuthoritativeMutation(
                    MessagingV2AuthoritativeMutation(
                        conversationId = envelope.conversationId,
                        messageId = event.targetMessageId,
                        kind = if (event.action == MessagingV2EventAction.REVOKE) {
                            MessageMutationKind.REVOKE
                        } else {
                            MessageMutationKind.DELETE
                        },
                    ),
                )
                return
            } else if (event.action in MISSING_TARGET_NO_OP_ACTIONS) {
                return
            } else {
                error("messaging_v2_event_target_missing:${event.targetMessageId}")
            }
        }

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
            MessagingV2EventAction.DELIVERY_RECEIPT -> receiptProjector.applyDeliveryReceipt(owner, existing, envelope)
            MessagingV2EventAction.READ_RECEIPT -> receiptProjector.applyReadReceipt(owner, existing, envelope, event)
            MessagingV2EventAction.PLAY_RECEIPT -> receiptProjector.applyPlayReceipt(owner, existing, envelope, event)
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
            app.database.scheduledMessageDao().deleteById(messageId, ownerUserId)
            ScheduledMessageScheduler.cancel(app, messageId)
        }
        step {
            if (app.notificationCenter.removeMessageReferences(messageId)) {
                MessageNotificationService.cancelMessage(app, conversationId)
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

    private companion object {
        val TERMINAL_ACTIONS = setOf(
            MessagingV2EventAction.DELETE,
            MessagingV2EventAction.REVOKE,
        )
        val MISSING_TARGET_NO_OP_ACTIONS = setOf(
            MessagingV2EventAction.DELETE,
            MessagingV2EventAction.DELIVERY_RECEIPT,
            MessagingV2EventAction.READ_RECEIPT,
            MessagingV2EventAction.PLAY_RECEIPT,
        )
    }
}
