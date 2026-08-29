package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageReaction
import kotlinx.serialization.Serializable

/** Encrypted application payload. Transport metadata is intentionally not duplicated here. */
@Serializable
data class MessagingV2Content(
    val version: Int = 1,
    val type: String,
    val body: String = "",
    val replyToMessageId: String? = null,
    val attachmentIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val expiresInSeconds: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
    /** Optional encrypted domain event. DATA messages leave this null. */
    val event: MessagingV2Event? = null,
)

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

object MessagingV2EventAction {
    const val EDIT = "EDIT"
    const val REVOKE = "REVOKE"
    const val DELETE = "DELETE"
    const val REACTION_SET = "REACTION_SET"
    /** Legacy compatibility only. Receivers may merge only the envelope sender's entry. */
    const val REACTION_SNAPSHOT = "REACTION_SNAPSHOT"
    const val DELIVERY_RECEIPT = "DELIVERY_RECEIPT"
    const val READ_RECEIPT = "READ_RECEIPT"
}
