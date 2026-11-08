package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure
import com.maodouchat.core.model.MessageType
import com.maodouchat.domain.messaging.isAttachmentContentCompatible

internal fun validateAttachmentContent(context: Context, uri: Uri, type: MessageType) {
    if (type == MessageType.FILE) return
    val header = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            buffer.copyOf(offset)
        }
    }.getOrNull()
    if (header == null || !isAttachmentContentCompatible(type, header)) {
        throw AttachmentCryptoException(AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT)
    }
}

private const val HEADER_BYTES = 16
