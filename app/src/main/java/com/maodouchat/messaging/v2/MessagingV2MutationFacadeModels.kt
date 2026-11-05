package com.maodouchat.messaging.v2

internal fun interface MessagingV2EventOutbox {
    suspend fun enqueue(
        conversationId: String,
        event: MessagingV2Event,
        groupRevision: Long?,
    )
}
