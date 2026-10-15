package com.maodouchat.data.repository

import com.maodouchat.data.model.MessageType

data class Preview(
    val text: String,
    val type: MessageType,
    val timestamp: Long
)
