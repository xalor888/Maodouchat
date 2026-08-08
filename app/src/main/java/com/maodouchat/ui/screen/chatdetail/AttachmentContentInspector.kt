package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.net.Uri
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure

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

internal fun isAttachmentContentCompatible(type: MessageType, header: ByteArray): Boolean = when (type) {
    MessageType.FILE -> true
    MessageType.IMAGE -> header.startsWith(0xFF, 0xD8, 0xFF)
    MessageType.GIF -> header.asciiPrefix("GIF87a") || header.asciiPrefix("GIF89a")
    MessageType.VIDEO -> header.hasIsoBaseMediaBrand() || header.startsWith(0x1A, 0x45, 0xDF, 0xA3)
    MessageType.VOICE -> header.hasIsoBaseMediaBrand()
    else -> false
}

private fun ByteArray.hasIsoBaseMediaBrand(): Boolean =
    size >= 8 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

private fun ByteArray.asciiPrefix(value: String): Boolean =
    size >= value.length && value.indices.all { this[it] == value[it].code.toByte() }

private fun ByteArray.startsWith(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

private const val HEADER_BYTES = 16
