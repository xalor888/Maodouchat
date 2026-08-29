package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageReaction

/** Keeps reaction mutations scoped to the authenticated envelope sender. */
object ReactionMutationPolicy {
    fun apply(
        existing: List<MessageReaction>,
        actorUserId: String,
        emoji: String?,
        reactedAt: Long,
    ): List<MessageReaction> {
        if (actorUserId.isBlank()) return existing
        val withoutActor = existing.filterNot { it.userId == actorUserId }
        val normalizedEmoji = emoji?.trim()?.takeIf { it.isNotEmpty() } ?: return withoutActor
        return withoutActor + MessageReaction(
            userId = actorUserId,
            emoji = normalizedEmoji,
            reactedAt = reactedAt,
        )
    }

    fun applyLegacySnapshot(
        existing: List<MessageReaction>,
        actorUserId: String,
        snapshot: List<MessageReaction>,
        fallbackReactedAt: Long,
    ): List<MessageReaction> {
        val actorReaction = snapshot.lastOrNull { it.userId == actorUserId }
        return apply(
            existing = existing,
            actorUserId = actorUserId,
            emoji = actorReaction?.emoji,
            reactedAt = actorReaction?.reactedAt ?: fallbackReactedAt,
        )
    }
}
