package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.ChatTextDisplayPolicy
import com.maodouchat.util.JsonFormat

/** Versioned read/write adapter for v2 content and the legacy `<meta>` representation. */
object ContentPayloadCodec {
    private const val STRUCTURED_VERSION = 2
    private const val META_PREFIX = "<meta>"
    private const val META_SUFFIX = "</meta>"

    fun encode(payload: DecodedContentPayload): MessagingV2Content = MessagingV2Content(
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

    fun decode(content: MessagingV2Content): DecodedContentPayload {
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
        return DecodedContentPayload(
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
    ): DecodedContentPayload {
        val metadata = message.parsedMeta()
        return DecodedContentPayload(
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
        // M01 子项 5：字面 `<meta>` 文本（无闭合 `</meta>`）不得被当作 metadata 标记而截断正文。
        val suffixIndex = rawBody.indexOf(META_SUFFIX, index + META_PREFIX.length)
        if (suffixIndex < 0) {
            return LegacyBody(ChatTextDisplayPolicy.unescapeHtmlEntities(rawBody), MessageMeta())
        }
        val encoded = rawBody.substring(index + META_PREFIX.length, suffixIndex)
        val metadata = runCatching { JsonFormat.fromJsonString(encoded) }.getOrDefault(MessageMeta())
        return LegacyBody(
            body = ChatTextDisplayPolicy.unescapeHtmlEntities(rawBody.substring(0, index)),
            metadata = metadata,
        )
    }

    private data class LegacyBody(val body: String, val metadata: MessageMeta)
}

