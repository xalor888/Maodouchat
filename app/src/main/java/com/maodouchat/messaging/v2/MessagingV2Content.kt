package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.ChatTextDisplayPolicy
import com.maodouchat.util.JsonFormat
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
     * trailing `<meta>` marker; readers accept that form through [ContentPayloadCodec], but new
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
data class ContentPayload(
    val type: MessageType,
    val body: String,
    val metadata: MessageMeta = MessageMeta(),
    val replyToMessageId: String? = metadata.replyToId,
    val attachmentIds: List<String> = metadata.attachmentId?.let(::listOf) ?: emptyList(),
    val mentionedUserIds: List<String> = metadata.mentions,
    val expiresInSeconds: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
)

/** Versioned read/write adapter for v2 content and the legacy `<meta>` representation. */
object ContentPayloadCodec {
    private const val STRUCTURED_VERSION = 2
    private const val META_PREFIX = "<meta>"
    private const val META_SUFFIX = "</meta>"

    fun encode(payload: ContentPayload): MessagingV2Content = MessagingV2Content(
        version = STRUCTURED_VERSION,
        type = payload.type.name,
        body = payload.body,
        replyToMessageId = payload.replyToMessageId,
        attachmentIds = payload.attachmentIds,
        mentionedUserIds = payload.mentionedUserIds,
        expiresInSeconds = payload.expiresInSeconds,
        attributes = payload.attributes,
        metadata = payload.metadata,
    )

    fun decode(content: MessagingV2Content): ContentPayload {
        val legacy = if (content.metadata == null) {
            decodeLegacyBody(content.body)
        } else {
            LegacyBody(content.body, MessageMeta())
        }
        val rawMetadata = content.metadata ?: legacy.metadata
        val metadata = rawMetadata.copy(
            replyToId = content.replyToMessageId ?: rawMetadata.replyToId,
            mentions = content.mentionedUserIds.ifEmpty { rawMetadata.mentions },
            attachmentId = content.attachmentIds.firstOrNull() ?: rawMetadata.attachmentId,
        )
        return ContentPayload(
            type = MessageType.fromWire(content.type),
            body = legacy.body,
            metadata = metadata,
            replyToMessageId = content.replyToMessageId ?: metadata.replyToId,
            attachmentIds = content.attachmentIds.ifEmpty {
                metadata.attachmentId?.let(::listOf) ?: emptyList()
            },
            mentionedUserIds = content.mentionedUserIds.ifEmpty { metadata.mentions },
            expiresInSeconds = content.expiresInSeconds,
            attributes = content.attributes,
        )
    }

    /** Reads legacy stored messages without creating a marker in the resulting payload. */
    fun fromLegacyMessage(
        message: Message,
        transportBody: String = message.parsedContent(),
        transportType: MessageType = message.type,
    ): ContentPayload {
        val metadata = message.parsedMeta()
        return ContentPayload(
            type = transportType,
            body = decodeLegacyBody(transportBody).body,
            metadata = metadata,
            replyToMessageId = metadata.replyToId,
            attachmentIds = metadata.attachmentId?.let(::listOf) ?: emptyList(),
            mentionedUserIds = metadata.mentions,
        )
    }

    /** Canonical local form: metadata belongs in `Message.meta`, never in `Message.content`. */
    fun normalizeLocalMessage(message: Message): Message = message.copy(
        content = message.parsedContent(),
        meta = message.parsedMeta(),
    )

    private fun decodeLegacyBody(rawBody: String): LegacyBody {
        val index = rawBody.lastIndexOf(META_PREFIX)
        if (index < 0) return LegacyBody(ChatTextDisplayPolicy.unescapeHtmlEntities(rawBody), MessageMeta())
        val encoded = rawBody.substring(index + META_PREFIX.length).substringBefore(META_SUFFIX)
        val metadata = runCatching { JsonFormat.fromJsonString(encoded) }.getOrDefault(MessageMeta())
        return LegacyBody(
            body = ChatTextDisplayPolicy.unescapeHtmlEntities(rawBody.substring(0, index)),
            metadata = metadata,
        )
    }

    private data class LegacyBody(val body: String, val metadata: MessageMeta)
}

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
