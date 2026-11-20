package com.maodouchat.attachment

import android.net.Uri
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.domain.messaging.LocalFileMetadata

internal data class AttachmentSendRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: LocalFileMetadata,
    val voiceDurationMs: Long?,
    val optimisticMessage: Message,
)
