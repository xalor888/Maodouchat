package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 转发预览的媒体标签种类：非媒体类型返回 null，调用方取正文前 40 字。
fun forwardPreviewText(type: MessageType): ChatMessagePreviewText? = when (type) {
    MessageType.IMAGE -> ChatMessagePreviewText.PREVIEW_IMAGE
    MessageType.GIF -> ChatMessagePreviewText.PREVIEW_GIF
    MessageType.STICKER -> ChatMessagePreviewText.PREVIEW_STICKER
    MessageType.LOCATION -> ChatMessagePreviewText.PREVIEW_LOCATION
    MessageType.VIDEO -> ChatMessagePreviewText.PREVIEW_VIDEO
    MessageType.VOICE -> ChatMessagePreviewText.PREVIEW_VOICE
    MessageType.FILE -> ChatMessagePreviewText.PREVIEW_FILE
    else -> null
}
