package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 消息类型 -> 附件种类；未列出的类型与原 else 一致回落 FILE。
fun attachmentKindForMessageType(type: MessageType): AttachmentKind =
    when (type) {
        MessageType.IMAGE -> AttachmentKind.IMAGE
        MessageType.VIDEO -> AttachmentKind.VIDEO
        MessageType.VOICE -> AttachmentKind.VOICE
        MessageType.FILE -> AttachmentKind.FILE
        MessageType.GIF -> AttachmentKind.GIF
        else -> AttachmentKind.FILE
    }
