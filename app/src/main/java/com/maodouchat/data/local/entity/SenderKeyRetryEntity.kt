package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "sender_key_retry_queue",
    primaryKeys = ["ownerUserId", "chatId"],
    indices = [
        Index("ownerUserId"),
        Index("nextAttemptAt"),
        Index(value = ["ownerUserId", "nextAttemptAt"])
    ]
)
data class SenderKeyRetryEntity(
    val ownerUserId: String,
    val chatId: String,
    val epoch: Long,
    val reason: String = "",
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
