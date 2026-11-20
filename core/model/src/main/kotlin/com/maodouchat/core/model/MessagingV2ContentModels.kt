package com.maodouchat.core.model

import kotlinx.serialization.Serializable

/**
 * Client-visible mutation carried inside the same durable device envelope as normal messages.
 * The server only stores/forwards this structure as ciphertext; ordering and idempotency are
 * enforced by the inbox coordinator before the local projection is changed.
 */
@Serializable
data class MessagingV2Event(
    val action: String,
    val targetMessageId: String,
    val content: String? = null,
    val editedAt: Long? = null,
    val reactionEmoji: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val status: String? = null,
    val throughMessageId: String? = null,
)
