package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun interface MessagingV2EventOutbox {
    suspend fun enqueue(
        conversationId: String,
        event: MessagingV2Event,
        groupRevision: Long?,
    )
}

internal data class MessagingV2MutationCommit(
    val localProjectionError: Throwable? = null,
)

/**
 * Owns the durable message-mutation boundary. UI callers may stage optimistic state, but a
 * mutation is accepted only after its encrypted event is durable in the v2 outbox.
 */
internal class MessagingV2MutationFacade(
    private val eventOutbox: MessagingV2EventOutbox,
    private val persistDeleted: suspend (String) -> Unit,
    private val persistRevoked: suspend (String, Message) -> Unit,
    private val persistEdited: suspend (Message) -> Message?,
    private val persistReaction: suspend (
        original: Message,
        reactions: List<MessageReaction>,
        actorUserId: String,
        reactionEmoji: String?,
    ) -> Unit,
    private val indexMessage: suspend (Message) -> Unit,
    private val cleanupAttachment: suspend (String) -> Unit,
    private val refreshConversationPreview: (String) -> Unit,
    private val isOwnerSessionCurrent: (String) -> Boolean,
    private val cleanupTerminalNotification: suspend (Message) -> Unit = {},
) {
    suspend fun delete(
        original: Message,
        ownerUserId: String,
        groupRevision: Long?,
    ): MessagingV2MutationCommit {
        requireCurrentOwner(ownerUserId)
        eventOutbox.enqueue(
            original.chatId,
            MessagingV2Event(
                action = MessagingV2EventAction.DELETE,
                targetMessageId = original.id,
            ),
            groupRevision,
        )
        return completeCommittedProjection(
            localProjection = {
                runAllConvergenceSteps(
                    { persistDeleted(original.id) },
                    { cleanupAttachment(original.id) },
                    { cleanupTerminalNotification(original) },
                )
            },
            refreshPreview = { refreshConversationPreview(original.chatId) },
        )
    }

    suspend fun revoke(
        original: Message,
        revoked: Message,
        ownerUserId: String,
        groupRevision: Long?,
    ): MessagingV2MutationCommit {
        require(original.id == revoked.id) { "messaging_v2_revoke_target_mismatch" }
        requireCurrentOwner(ownerUserId)
        eventOutbox.enqueue(
            original.chatId,
            MessagingV2Event(
                action = MessagingV2EventAction.REVOKE,
                targetMessageId = original.id,
                content = revoked.content,
                editedAt = revoked.editedAt ?: System.currentTimeMillis(),
            ),
            groupRevision,
        )
        return completeCommittedProjection(
            localProjection = {
                runAllConvergenceSteps(
                    { persistRevoked(original.id, revoked) },
                    { cleanupAttachment(original.id) },
                    { cleanupTerminalNotification(original) },
                )
            },
            refreshPreview = { refreshConversationPreview(original.chatId) },
        )
    }

    suspend fun edit(
        updated: Message,
        ownerUserId: String,
        groupRevision: Long?,
        indexForSearch: Boolean = true,
        refreshPreview: Boolean = true,
    ): MessagingV2MutationCommit {
        requireCurrentOwner(ownerUserId)
        eventOutbox.enqueue(
            updated.chatId,
            MessagingV2Event(
                action = MessagingV2EventAction.EDIT,
                targetMessageId = updated.id,
                content = updated.content,
                editedAt = updated.editedAt ?: System.currentTimeMillis(),
            ),
            groupRevision,
        )
        return completeCommittedProjection(
            localProjection = {
                val applied = persistEdited(updated)
                if (indexForSearch && applied != null) indexMessage(applied)
            },
            refreshPreview = if (refreshPreview) {
                { refreshConversationPreview(updated.chatId) }
            } else {
                null
            },
        )
    }

    suspend fun setReaction(
        original: Message,
        reactions: List<MessageReaction>,
        reactionEmoji: String?,
        ownerUserId: String,
        groupRevision: Long?,
    ): MessagingV2MutationCommit {
        requireCurrentOwner(ownerUserId)
        eventOutbox.enqueue(
            original.chatId,
            MessagingV2Event(
                action = MessagingV2EventAction.REACTION_SET,
                targetMessageId = original.id,
                reactionEmoji = reactionEmoji,
            ),
            groupRevision,
        )
        return completeCommittedProjection(
            localProjection = {
                persistReaction(original, reactions, ownerUserId, reactionEmoji)
            },
        )
    }

    /**
     * The encrypted event is already durable when this runs. Finish short local
     * convergence work despite parent cancellation and report errors as warnings;
     * callers must never roll back an already committed mutation.
     */
    private suspend fun completeCommittedProjection(
        localProjection: suspend () -> Unit,
        refreshPreview: (() -> Unit)? = null,
    ): MessagingV2MutationCommit {
        var projectionError: Throwable? = try {
            withContext(Dispatchers.IO + NonCancellable) { localProjection() }
            null
        } catch (error: Exception) {
            error
        }
        try {
            refreshPreview?.invoke()
        } catch (error: Exception) {
            if (projectionError == null) projectionError = error else projectionError.addSuppressed(error)
        }
        return MessagingV2MutationCommit(projectionError)
    }

    private fun requireCurrentOwner(ownerUserId: String) {
        require(ownerUserId.isNotBlank() && isOwnerSessionCurrent(ownerUserId)) {
            "messaging_v2_owner_session_changed"
        }
    }

    private suspend fun runAllConvergenceSteps(vararg operations: suspend () -> Unit) {
        var firstFailure: Exception? = null
        operations.forEach { operation ->
            try {
                operation()
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }
}
