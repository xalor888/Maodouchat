package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "message_search_documents",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId"), Index("timestamp"), Index(value = ["chatId", "timestamp"])]
)
data class MessageSearchDocumentEntity(
    @androidx.room.PrimaryKey
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val searchableText: String,
    val contentHash: String,
    val timestamp: Long,
    val indexedAt: Long,
    val messageType: String = "TEXT"
)

@Entity(
    tableName = "message_search_tokens",
    primaryKeys = ["messageId", "token"],
    foreignKeys = [
        ForeignKey(
            entity = MessageSearchDocumentEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("token"), Index("chatId"), Index(value = ["token", "timestamp"])]
)
data class MessageSearchTokenEntity(
    val messageId: String,
    val token: String,
    val chatId: String,
    val timestamp: Long
)

data class MessageSearchFingerprint(
    val messageId: String,
    val contentHash: String
)

data class MessageSearchMatchRow(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val searchableText: String,
    val contentHash: String,
    val timestamp: Long,
    val indexedAt: Long,
    val matchCount: Int,
    val messageType: String = "TEXT"
)
