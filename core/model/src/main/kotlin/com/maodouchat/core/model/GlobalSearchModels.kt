package com.maodouchat.core.model

enum class GlobalSearchMode { KEYWORD, AI }

data class GlobalSearchHit(
    val chatId: String,
    val messageId: String,
    val chatName: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val semanticScore: Double? = null,
    val messageType: String = "TEXT"
)
