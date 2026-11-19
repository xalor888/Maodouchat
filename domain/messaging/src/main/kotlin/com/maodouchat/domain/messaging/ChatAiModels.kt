package com.maodouchat.domain.messaging

data class PreparedAiFile(val base64: String, val fileName: String, val mimeType: String)

data class AiRequestSnapshot(val userId: String, val chatId: String)

data class AiContextSenders(
    val currentUserId: String,
    val currentUserLabel: String,
    val fallbackLabel: String,
    val namesByUserId: Map<String, String>
)
