package com.maodouchat.domain.messaging

data class SenderKeyRetryTask(
    val ownerUserId: String,
    val chatId: String,
    val epoch: Long,
    val reason: String = "",
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val updatedAt: Long
)
