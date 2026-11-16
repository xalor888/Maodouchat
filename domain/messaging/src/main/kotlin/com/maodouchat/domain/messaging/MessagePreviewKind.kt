package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 会话列表媒体预览文案种类；9 个显式类型各一桶，其余类型共用 OTHER 桶。
enum class MessagePreviewKind {
    IMAGE,
    GIF,
    STICKER,
    LOCATION,
    VOICE,
    VIDEO,
    FILE,
    NUDGE,
    SYSTEM,
    OTHER,
}

fun messagePreviewKind(type: MessageType): MessagePreviewKind =
    when (type) {
        MessageType.IMAGE -> MessagePreviewKind.IMAGE
        MessageType.GIF -> MessagePreviewKind.GIF
        MessageType.STICKER -> MessagePreviewKind.STICKER
        MessageType.LOCATION -> MessagePreviewKind.LOCATION
        MessageType.VOICE -> MessagePreviewKind.VOICE
        MessageType.VIDEO -> MessagePreviewKind.VIDEO
        MessageType.FILE -> MessagePreviewKind.FILE
        MessageType.NUDGE -> MessagePreviewKind.NUDGE
        MessageType.SYSTEM -> MessagePreviewKind.SYSTEM
        else -> MessagePreviewKind.OTHER
    }
