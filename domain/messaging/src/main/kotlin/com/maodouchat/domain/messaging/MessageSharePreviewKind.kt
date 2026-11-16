package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 系统分享时无本地文件可直发、取文本预览的种类；未列出的类型与原 else 一致回落 FILE。
enum class MessageSharePreviewKind {
    IMAGE,
    GIF,
    STICKER,
    LOCATION,
    FILE,
}

fun messageSharePreviewKind(type: MessageType): MessageSharePreviewKind =
    when (type) {
        MessageType.IMAGE -> MessageSharePreviewKind.IMAGE
        MessageType.GIF -> MessageSharePreviewKind.GIF
        MessageType.STICKER -> MessageSharePreviewKind.STICKER
        MessageType.LOCATION -> MessageSharePreviewKind.LOCATION
        else -> MessageSharePreviewKind.FILE
    }
