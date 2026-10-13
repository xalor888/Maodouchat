package com.maodouchat.attachment

import android.net.Uri
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache

internal data class AttachmentSendRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: MediaCache.LocalFileMetadata,
    val voiceDurationMs: Long?,
    val optimisticMessage: Message,
)

internal sealed interface AttachmentSendResult {
    data object ExistingTransfer : AttachmentSendResult
    data class Queued(val message: Message) : AttachmentSendResult
}
