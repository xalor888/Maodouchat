package com.maodouchat.core.model

data class ArchiveSuggestion(
    val chatId: String,
    val score: Int,
    val reason: String
)
