package com.maodouchat.data.repository

import com.maodouchat.core.model.MessageType

data class Preview(
    val text: String,
    val type: MessageType,
    val timestamp: Long
)
