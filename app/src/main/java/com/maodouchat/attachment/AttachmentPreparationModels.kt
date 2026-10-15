package com.maodouchat.attachment

import android.net.Uri
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.LocalFileMetadata

internal data class AttachmentPreparationRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: LocalFileMetadata,
    val voiceDurationMs: Long?
)

internal data class PreparedAttachment(
    val chatId: String,
    val sourceUri: String,
    val fileName: String,
    val mimeType: String,
    val plainSize: Long
)
