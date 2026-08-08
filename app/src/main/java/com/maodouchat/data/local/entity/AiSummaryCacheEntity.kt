package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_summary_cache",
    indices = [Index("chatId")]
)
data class AiSummaryCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val chatId: String,
    val startMessageId: String,
    val endMessageId: String,
    val messageCount: Int,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis()
)
