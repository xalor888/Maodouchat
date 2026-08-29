package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "messaging_v2_receipts",
    primaryKeys = ["ownerUserId", "messageId", "recipientUserId"],
    indices = [
        Index(value = ["ownerUserId", "conversationId", "messageId"]),
        Index(value = ["ownerUserId", "recipientUserId", "readAt"]),
    ],
)
data class MessagingV2ReceiptEntity(
    val ownerUserId: String,
    val messageId: String,
    val conversationId: String,
    val recipientUserId: String,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
