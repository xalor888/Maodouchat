package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2Event
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
    /**
     * Structured message metadata introduced in content version 2. Version 1 encoded this in a
     * trailing `<meta>` marker; readers accept that form through [com.maodouchat.domain.messaging.ContentPayloadCodec], but new
     * command paths do not create it.
     */
    val metadata: MessageMeta? = null,
    /** Optional encrypted domain event. DATA messages leave this null. */
    val event: MessagingV2Event? = null,
)

/**
 * Typed domain representation of a user-visible encrypted payload.
 *
 * `Message.content` remains a local display field. This type is the application boundary that
 * keeps transport metadata structured rather than treating the display text as a wire format.
 */
data class DecodedContentPayload(
    val type: MessageType,
    val body: String,
    val metadata: MessageMeta = MessageMeta(),
    val replyToMessageId: String? = metadata.replyToId,
    val attachmentIds: List<String> = metadata.attachmentId?.let(::listOf) ?: emptyList(),
    val mentionedUserIds: List<String> = metadata.mentions,
    val expiresInSeconds: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
)

data class LegacyBody(val body: String, val metadata: MessageMeta)
