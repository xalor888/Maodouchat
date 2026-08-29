package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import kotlinx.coroutines.CancellationException

internal sealed interface MessageMutationProjection {
    data class Remove(val messageId: String) : MessageMutationProjection
    data class Set(val message: Message) : MessageMutationProjection
}

internal sealed interface ConversationMessageMutationOutcome {
    data class Applied(
        val removePinnedReference: Boolean,
        val localProjectionError: Throwable? = null,
        val authoritativeElsewhere: Boolean = false,
    ) : ConversationMessageMutationOutcome

    data class Failed(
        val error: Throwable,
        val rolledBack: Boolean,
    ) : ConversationMessageMutationOutcome

    data object Ignored : ConversationMessageMutationOutcome
}

/**
 * Owns optimistic mutation ordering around the durable v2 event boundary.
 * Only failures before outbox commit may roll UI state back.
 */
internal class ConversationMessageMutationCoordinator(
    private val facade: MessagingV2MutationFacade,
    private val tracker: MessageMutationTracker = MessageMutationTracker(),
) {
    suspend fun delete(
        original: Message,
        ownerUserId: String,
        groupRevision: Long?,
        project: (MessageMutationProjection) -> Unit,
    ): ConversationMessageMutationOutcome = execute(
        original = original,
        kind = MessageMutationKind.DELETE,
        optimistic = MessageMutationProjection.Remove(original.id),
        rollback = MessageMutationProjection.Set(original),
        removePinnedReference = true,
        project = project,
    ) {
        facade.delete(original, ownerUserId, groupRevision)
    }

    suspend fun revoke(
        original: Message,
        revoked: Message,
        ownerUserId: String,
        groupRevision: Long?,
        project: (MessageMutationProjection) -> Unit,
    ): ConversationMessageMutationOutcome = execute(
        original = original,
        kind = MessageMutationKind.REVOKE,
        optimistic = MessageMutationProjection.Set(revoked),
        rollback = MessageMutationProjection.Set(original),
        removePinnedReference = true,
        project = project,
    ) {
        facade.revoke(original, revoked, ownerUserId, groupRevision)
    }

    suspend fun edit(
        original: Message,
        updated: Message,
        ownerUserId: String,
        groupRevision: Long?,
        project: (MessageMutationProjection) -> Unit,
    ): ConversationMessageMutationOutcome = execute(
        original = original,
        kind = MessageMutationKind.EDIT,
        optimistic = MessageMutationProjection.Set(updated),
        rollback = MessageMutationProjection.Set(original),
        removePinnedReference = false,
        project = project,
    ) {
        facade.edit(updated, ownerUserId, groupRevision)
    }

    fun observeAuthoritative(messageId: String, kind: MessageMutationKind) {
        tracker.observeAuthoritative(messageId, kind)
    }

    private suspend fun execute(
        original: Message,
        kind: MessageMutationKind,
        optimistic: MessageMutationProjection,
        rollback: MessageMutationProjection,
        removePinnedReference: Boolean,
        project: (MessageMutationProjection) -> Unit,
        mutation: suspend () -> MessagingV2MutationCommit,
    ): ConversationMessageMutationOutcome {
        val ticket = tracker.begin(original.id, kind) ?: return ConversationMessageMutationOutcome.Ignored
        try {
            project(optimistic)
        } catch (error: Throwable) {
            tracker.shouldRollback(ticket)
            throw error
        }
        return try {
            val commit = mutation()
            tracker.complete(ticket)
            ConversationMessageMutationOutcome.Applied(
                removePinnedReference = removePinnedReference,
                localProjectionError = commit.localProjectionError,
            )
        } catch (cancelled: CancellationException) {
            if (tracker.shouldRollback(ticket)) project(rollback)
            throw cancelled
        } catch (error: Exception) {
            val rolledBack = tracker.shouldRollback(ticket)
            if (rolledBack) project(rollback)
            if (rolledBack) {
                ConversationMessageMutationOutcome.Failed(error, rolledBack = true)
            } else {
                ConversationMessageMutationOutcome.Applied(
                    removePinnedReference = removePinnedReference,
                    authoritativeElsewhere = true,
                )
            }
        }
    }
}
