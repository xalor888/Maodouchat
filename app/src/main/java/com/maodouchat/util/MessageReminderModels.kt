package com.maodouchat.util

data class MessageReminder(
    val id: String,
    val chatId: String,
    val messageId: String,
    val messagePreview: String,
    val remindAtMillis: Long,
    val createdAtMillis: Long,
    val ownerUserId: String
)
