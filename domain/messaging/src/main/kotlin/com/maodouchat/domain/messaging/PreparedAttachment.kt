package com.maodouchat.domain.messaging

data class PreparedAttachment(
    val chatId: String,
    val sourceUri: String,
    val fileName: String,
    val mimeType: String,
    val plainSize: Long
)
