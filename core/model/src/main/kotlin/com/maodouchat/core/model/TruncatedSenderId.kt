package com.maodouchat.core.model

fun truncatedSenderId(senderId: String): String? {
    val id = senderId.trim()
    if (id.isEmpty()) return null
    return if (id.length <= 8) id else id.take(8)
}
