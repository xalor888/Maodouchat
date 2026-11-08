package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

/** 发件箱可直接带附件引用的消息类型：纯文本/事件等类型走别的入队路径。 */
fun isAttachmentMessageType(type: MessageType): Boolean = type in ATTACHMENT_MESSAGE_TYPES

val ATTACHMENT_MESSAGE_TYPES = setOf(
    MessageType.IMAGE,
    MessageType.GIF,
    MessageType.VIDEO,
    MessageType.VOICE,
    MessageType.FILE,
)
