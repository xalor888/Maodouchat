package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 密聊本机无活动 TTL 心跳。身份以 chats.chatType=SECRET 为准，本表不是开关。
 */
@Entity(tableName = "secret_chats")
data class SecretChatEntity(
    @PrimaryKey val chatId: String,
    val enabledAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis()
)
