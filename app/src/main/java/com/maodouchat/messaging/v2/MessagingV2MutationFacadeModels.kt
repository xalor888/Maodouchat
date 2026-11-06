package com.maodouchat.messaging.v2

import com.maodouchat.core.model.MessagingV2Event

internal fun interface MessagingV2EventOutbox {
    suspend fun enqueue(
        conversationId: String,
        event: MessagingV2Event,
        groupRevision: Long?,
    )
}
