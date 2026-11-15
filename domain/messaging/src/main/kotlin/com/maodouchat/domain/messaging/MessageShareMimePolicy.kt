package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 系统分享（ACTION_SEND）时按消息类型决定文件 MIME；不可作文件分享的类型回 null 走文本分享。
object MessageShareMimePolicy {

    fun shareMimeType(type: MessageType): String? = when (type) {
        MessageType.IMAGE, MessageType.GIF -> "image/*"
        MessageType.VIDEO -> "video/*"
        MessageType.FILE -> "application/octet-stream"
        else -> null
    }
}
