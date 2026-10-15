package com.maodouchat.attachment

import android.net.Uri
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.LocalFileMetadata

internal data class AttachmentSendCommand(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val chatId: String,
    val senderId: String,
    val existingMessage: Message? = null,
    val forwardedFrom: String? = null,
    val metadataOverride: LocalFileMetadata? = null,
    val voiceDurationMs: Long? = null,
    val viewOnce: Boolean = false,
    val spoilerMedia: Boolean = false,
)

internal sealed interface AttachmentSendWorkflowResult {
    data class Existing(val message: Message) : AttachmentSendWorkflowResult
    data class Queued(val message: Message) : AttachmentSendWorkflowResult
}

internal class AttachmentTooLargeException : IllegalStateException("attachment_too_large")
