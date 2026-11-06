package com.maodouchat.network

import kotlinx.serialization.Serializable
import com.maodouchat.data.model.MessageReaction

@Serializable
data class MessageDto(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String = "TEXT",
    val timestamp: Long,
    val status: String = "SENT",
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val reactions: List<MessageReaction> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
)
