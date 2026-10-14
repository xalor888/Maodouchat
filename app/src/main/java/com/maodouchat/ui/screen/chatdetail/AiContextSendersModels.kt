package com.maodouchat.ui.screen.chatdetail

internal data class AiContextSenders(
    val currentUserId: String,
    val currentUserLabel: String,
    val fallbackLabel: String,
    val namesByUserId: Map<String, String>
)
