package com.maodouchat.core.model

fun interface MessagingV2EventOutbox {
    suspend fun enqueue(
        conversationId: String,
        event: MessagingV2Event,
        groupRevision: Long?,
    )
}
