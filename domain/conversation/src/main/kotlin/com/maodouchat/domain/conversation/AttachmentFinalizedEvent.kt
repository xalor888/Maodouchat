package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
data class AttachmentFinalizedEvent(
    val message: Message,
    val sessionGeneration: Long,
)
