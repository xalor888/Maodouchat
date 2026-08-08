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

/** 多设备 DELETE/REVOKE/EDIT 变更回放条目 */
@Serializable
data class MessageMutationDto(
    val id: String,
    val chatId: String,
    val messageId: String,
    val action: String,
    val actorId: String,
    val content: String? = null,
    val editedAt: Long? = null,
    val createdAt: Long
)

@Serializable
data class UnreadWindowDto(
    val messageIds: List<String> = emptyList(),
    val totalCount: Int = 0,
    val truncated: Boolean = false
)

@Serializable
data class SendMessageRequest(
    val chatId: String,
    val content: String,
    val type: String = "TEXT",
    val id: String? = null,
    val sealedSender: Boolean = false,
    val sealedSenderCertificate: String? = null,
    val silent: Boolean = false
)

@Serializable
data class UpdateMessageReactionRequest(val emoji: String)

@Serializable
data class MessageReactionUpdatedResponse(
    val chatId: String,
    val messageId: String,
    val userId: String,
    val reactions: List<MessageReaction> = emptyList()
)

@Serializable
data class StarMessageResponse(val status: String = "ok", val starred: Boolean = false)

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
data class ReadReceiptDto(val userId: String, val readAt: Long)

@Serializable
data class UpdateDisappearingMessagesRequest(val seconds: Int = 0)

@Serializable
data class DisappearingMessagesResponse(
    val chatId: String,
    val seconds: Int,
    val updatedAt: Long = 0
)

@Serializable
data class MessageExpiresDto(
    val messageId: String,
    val chatId: String,
    val expiresAt: Long
)

@Serializable
data class UpdateStatusRequest(val messageId: String, val status: String)

@Serializable
data class MarkReadResponse(val status: String, val updated: Int)

@Serializable
data class BatchReadRequest(val chatIds: List<String> = emptyList())
