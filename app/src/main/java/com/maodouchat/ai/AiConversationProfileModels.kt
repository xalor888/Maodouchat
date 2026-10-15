package com.maodouchat.ai

import kotlinx.serialization.Serializable

@Serializable
data class LocalStats(
    val messageCount: Int = 0,
    val activeDays: Int = 0,
    val morning: Int = 0,
    val afternoon: Int = 0,
    val evening: Int = 0,
    val night: Int = 0,
    val emojiCount: Int = 0,
    val topTerms: List<String> = emptyList()
)

data class ConversationProfile(
    val chatId: String,
    val local: LocalStats,
    val narrative: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
