package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

val RELIABLE_ATTACHMENT_TYPES = setOf(
    MessageType.FILE,
    MessageType.IMAGE,
    MessageType.GIF,
    MessageType.VIDEO,
    MessageType.VOICE
)
val AUTO_DOWNLOAD_MEDIA_TYPES = RELIABLE_ATTACHMENT_TYPES - MessageType.FILE
