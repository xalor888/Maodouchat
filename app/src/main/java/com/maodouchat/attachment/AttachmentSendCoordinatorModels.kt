package com.maodouchat.attachment

import android.net.Uri
import com.maodouchat.data.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.LocalFileMetadata

internal data class AttachmentSendRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: LocalFileMetadata,
    val voiceDurationMs: Long?,
    val optimisticMessage: Message,
)

internal sealed interface AttachmentSendResult {
    data object ExistingTransfer : AttachmentSendResult
    data class Queued(val message: Message) : AttachmentSendResult
}
