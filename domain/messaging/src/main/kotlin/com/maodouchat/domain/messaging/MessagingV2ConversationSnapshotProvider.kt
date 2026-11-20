package com.maodouchat.domain.messaging

fun interface MessagingV2ConversationSnapshotProvider {
    suspend fun get(token: String, conversationId: String): MessagingV2ConversationSnapshot
}
