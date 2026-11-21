package com.maodouchat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StarMessageResponse(val status: String = "ok", val starred: Boolean = false)

@Serializable
data class StarredMessageRefDto(
    val messageId: String,
    val chatId: String,
    val starredAt: Long,
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
