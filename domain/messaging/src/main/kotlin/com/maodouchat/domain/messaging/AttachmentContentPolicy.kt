package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

/** 附件内容兼容判定：按文件头魔数校验声明类型与实际内容是否相符。 */
fun isAttachmentContentCompatible(type: MessageType, header: ByteArray): Boolean = when (type) {
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
