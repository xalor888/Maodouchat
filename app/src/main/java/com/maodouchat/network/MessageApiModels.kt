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

@Serializable
data class StarMessageResponse(val status: String = "ok", val starred: Boolean = false)

@Serializable
data class StarredMessageRefDto(
    val messageId: String,
    val chatId: String,
    val starredAt: Long,
)

@Serializable
data class PinnedMessageDto(
    val chatId: String,
    val messageId: String,
    val pinnedBy: String,
    val pinnedAt: Long
)

@Serializable
data class PinnedMessagesListResponse(
    val chatId: String,
    val pins: List<PinnedMessageDto> = emptyList()
)

@Serializable
data class TogglePinResponse(
    val status: String = "ok",
    val pinned: Boolean = false,
    val pins: List<PinnedMessageDto> = emptyList()
)

@Serializable
data class UpdateDisappearingMessagesRequest(val seconds: Int = 0)

@Serializable
data class DisappearingMessagesResponse(
    val chatId: String,
    val seconds: Int,
    val updatedAt: Long = 0
)

@Serializable
data class MarkReadResponse(val status: String, val updated: Int)

@Serializable
data class MarkReadRequest(val throughId: String? = null)
