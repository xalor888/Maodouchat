package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal sealed interface ConversationReactionOutcome {
    data class Applied(
        val localProjectionError: Throwable? = null,
    ) : ConversationReactionOutcome

    data class Failed(
        val error: Throwable,
    ) : ConversationReactionOutcome

    data object Ignored : ConversationReactionOutcome
}

/** Serializes actor-scoped reaction toggles per message around the durable v2 commit boundary. */
internal class ConversationReactionCoordinator(
    private val facade: MessagingV2MutationFacade,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class ReactionLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val locks = ConcurrentHashMap<String, ReactionLock>()

    suspend fun toggle(
        messageId: String,
        emoji: String,
        ownerUserId: String,
        groupRevision: () -> Long?,
        currentMessage: (String) -> Message?,
        project: (Message) -> Unit,
    ): ConversationReactionOutcome {
        val normalizedEmoji = emoji.trim()
        if (messageId.isBlank() || normalizedEmoji.isBlank() || ownerUserId.isBlank()) {
            return ConversationReactionOutcome.Ignored
        }
        val lock = locks.compute(messageId) { _, current ->
            (current ?: ReactionLock()).also { it.users++ }
        } ?: return ConversationReactionOutcome.Ignored

        return try {
            lock.mutex.withLock {
                val original = currentMessage(messageId)
                    ?.takeUnless { it.type == MessageType.REVOKED }
                    ?: return@withLock ConversationReactionOutcome.Ignored
                val actorChoice = normalizedEmoji.takeUnless { candidate ->
                    original.reactions.any {
                        it.userId == ownerUserId && it.emoji == candidate
                    }
                }
                val optimistic = original.copy(
                    reactions = ReactionMutationPolicy.apply(
                        existing = original.reactions,
                        actorUserId = ownerUserId,
                        emoji = actorChoice,
                        reactedAt = clock(),
                    ),
                )
                project(optimistic)
                try {
                    val commit = facade.setReaction(
                        original = original,
                        reactions = optimistic.reactions,
                        reactionEmoji = actorChoice,
                        ownerUserId = ownerUserId,
                        groupRevision = groupRevision(),
                    )
                    ConversationReactionOutcome.Applied(commit.localProjectionError)
                } catch (cancelled: CancellationException) {
                    rollbackActorChoice(
                        messageId = messageId,
                        ownerUserId = ownerUserId,
                        original = original,
                        currentMessage = currentMessage,
                        project = project,
                    )
                    throw cancelled
                } catch (error: Exception) {
                    rollbackActorChoice(
                        messageId = messageId,
                        ownerUserId = ownerUserId,
                        original = original,
                        currentMessage = currentMessage,
                        project = project,
                    )
                    ConversationReactionOutcome.Failed(error)
                }
            }
        } finally {
            locks.computeIfPresent(messageId) { _, current ->
                if (current !== lock) {
                    current
                } else if (current.users > 1) {
                    current.apply { users-- }
                } else {
                    null
                }
            }
        }
    }

    private fun rollbackActorChoice(
        messageId: String,
        ownerUserId: String,
        original: Message,
        currentMessage: (String) -> Message?,
        project: (Message) -> Unit,
    ) {
        val current = currentMessage(messageId)
            ?.takeUnless { it.type == MessageType.REVOKED }
            ?: return
        val originalActorReaction = original.reactions.lastOrNull { it.userId == ownerUserId }
        project(
            current.copy(
                reactions = ReactionMutationPolicy.apply(
                    existing = current.reactions,
                    actorUserId = ownerUserId,
                    emoji = originalActorReaction?.emoji,
                    reactedAt = originalActorReaction?.reactedAt ?: clock(),
                ),
            ),
        )
    }
}
