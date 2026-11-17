package com.maodouchat.domain.conversation

data class ArchiveSuggestion(
    val chatId: String,
    val score: Int,
    val reason: String
)
