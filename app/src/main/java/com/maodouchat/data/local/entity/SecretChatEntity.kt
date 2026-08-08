package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only secret chat (密聊) flag for a conversation.
 * Forces FLAG_SECURE + blind watermark on this device; not a server mode.
 * lastActivityAt 供密聊无活动 TTL 清扫（SecretSessionTtl.sweepExpired）使用。
 */
@Entity(tableName = "secret_chats")
data class SecretChatEntity(
    @PrimaryKey val chatId: String,
    val enabledAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis()
)
