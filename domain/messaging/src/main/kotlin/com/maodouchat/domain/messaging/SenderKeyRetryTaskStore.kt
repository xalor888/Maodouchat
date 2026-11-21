package com.maodouchat.domain.messaging

interface SenderKeyRetryTaskStore {
    suspend fun get(ownerUserId: String, chatId: String): SenderKeyRetryTask?
    suspend fun upsert(task: SenderKeyRetryTask)
}
