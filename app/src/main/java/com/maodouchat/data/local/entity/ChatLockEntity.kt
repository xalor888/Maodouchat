package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单聊/群聊 PIN 锁：打开该聊天时需输入 4 位 PIN */
@Entity(tableName = "chat_locks")
data class ChatLockEntity(
    @PrimaryKey val chatId: String,
    val pinHash: String,        // SHA-256(pin + salt)
    val salt: String,           // 16 字节 hex
    val createdAt: Long = System.currentTimeMillis()
)