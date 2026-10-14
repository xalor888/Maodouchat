package com.maodouchat.ui.component

import androidx.compose.runtime.Immutable
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.MessageStatus

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
