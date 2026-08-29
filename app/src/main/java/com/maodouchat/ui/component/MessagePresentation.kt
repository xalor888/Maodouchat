package com.maodouchat.ui.component

import androidx.compose.runtime.Immutable
import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType

/** Render-ready message data. Renderers must not decode message bodies or metadata. */
@Immutable
data class MessagePresentation(
    val id: String,
    val chatId: String,
    val senderId: String,
    val type: MessageType,
    val body: String,
    val timestamp: Long,
    val status: MessageStatus,
    val editedAt: Long?,
    val starred: Boolean,
    val expiresAt: Long?,
    val meta: MessageMetaPresentation,
    val reactions: List<MessageReactionPresentation>,
    val location: LocationPresentation?,
    val attachment: AttachmentPresentation?,
    val file: FilePresentation?,
    val requiresDecryptPlaceholder: Boolean,
) {
    val isHidden: Boolean get() = type.isHidden
}

@Immutable
data class MessageMetaPresentation(
    val mentions: List<String>,
    val forwardedFrom: String?,
    val voiceTranscript: String?,
    val voiceDurationMs: Long?,
    val markdown: Boolean,
    val aiAssisted: Boolean,
    val silent: Boolean,
    val inlineKeyboard: List<List<InlineKeyboardButtonPresentation>>,
)

@Immutable
data class InlineKeyboardButtonPresentation(
    val text: String,
    val callbackData: String,
)

@Immutable
data class MessageReactionPresentation(
    val userId: String,
    val emoji: String,
    val reactedAt: Long,
)

@Immutable
data class AttachmentPresentation(
    val uri: String,
    val attachmentId: String?,
    val viewOnce: Boolean,
    val viewOnceOpened: Boolean,
    val spoiler: Boolean,
    val spoilerRevealed: Boolean,
)

@Immutable
data class FilePresentation(
    val uri: String,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

@Immutable
data class LocationPresentation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val label: String,
    val capturedAt: Long,
    val live: Boolean,
    val liveUntil: Long?,
    val sessionId: String?,
)

/** Converts domain messages to stable Compose presentation data at the UI boundary. */
object MessagePresentationMapper {
    fun map(message: Message): MessagePresentation {
        val body = message.parsedContent()
        val meta = message.parsedMeta()
        return MessagePresentation(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            type = message.type,
            body = body,
            timestamp = message.timestamp,
            status = message.status,
            editedAt = message.editedAt,
            starred = message.starred,
            expiresAt = message.expiresAt,
            meta = MessageMetaPresentation(
                mentions = meta.mentions,
                forwardedFrom = meta.forwardedFrom?.takeIf(String::isNotBlank),
                voiceTranscript = meta.voiceTranscript?.takeIf(String::isNotBlank),
                voiceDurationMs = meta.voiceDurationMs?.takeIf { it > 0L },
                markdown = meta.markdown,
                aiAssisted = meta.aiAssisted,
                silent = meta.silent,
                inlineKeyboard = meta.inlineKeyboard.map { row ->
                    row.map { button ->
                        InlineKeyboardButtonPresentation(button.text, button.callbackData)
                    }
                },
            ),
            reactions = message.reactions.map { reaction ->
                MessageReactionPresentation(
                    userId = reaction.userId,
                    emoji = reaction.emoji,
                    reactedAt = reaction.reactedAt,
                )
            },
            location = message.parsedLocation()?.toPresentation(),
            attachment = if (message.type in ATTACHMENT_TYPES) {
                AttachmentPresentation(
                    uri = body,
                    attachmentId = meta.attachmentId,
                    viewOnce = meta.viewOnce && message.type in VIEW_ONCE_TYPES,
                    viewOnceOpened = meta.viewOnceOpened,
                    spoiler = meta.spoilerMedia,
                    spoilerRevealed = meta.spoilerRevealed,
                )
            } else {
                null
            },
            file = if (message.type == MessageType.FILE) {
                FilePresentation(
                    uri = body,
                    name = meta.fileName?.takeIf(String::isNotBlank) ?: fileNameFromUri(body),
                    mimeType = meta.fileMimeType?.takeIf(String::isNotBlank),
                    sizeBytes = meta.fileSizeBytes?.takeIf { it >= 0L },
                )
            } else {
                null
            },
            requiresDecryptPlaceholder = message.type in TEXT_TYPES &&
                com.maodouchat.data.repository.ChatListPreviewPolicy.isSignalWireEnvelope(body),
        )
    }

    private fun LocationPayload.toPresentation() = LocationPresentation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        label = label,
        capturedAt = capturedAt,
        live = live,
        liveUntil = liveUntil,
        sessionId = sessionId,
    )

    private fun fileNameFromUri(uri: String): String? = runCatching {
        java.net.URI(uri).path
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private val TEXT_TYPES = setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER)
    private val ATTACHMENT_TYPES = setOf(
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE,
        MessageType.FILE,
    )
    private val VIEW_ONCE_TYPES = setOf(MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO)
}
