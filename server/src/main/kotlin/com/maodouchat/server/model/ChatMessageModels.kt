package com.maodouchat.server.model

import kotlinx.serialization.Serializable


@Serializable
data class ChatResponse(
    val id: String, val participants: List<UserResponse>, val lastMessage: String = "",
    val lastMessageType: String = "TEXT", val lastMessageTime: Long = 0, val unreadCount: Int = 0,
    val isGroup: Boolean = false, val groupName: String? = null, val groupAnnouncement: String? = null, val groupAvatar: String? = null,
    val memberRevision: Long = 0, val pinnedAt: Long = 0, val notificationsMuted: Boolean = false,
    val archived: Boolean = false, val markedUnread: Boolean = false, val settingsUpdatedAt: Long = 0,
    val disappearingMessageSeconds: Int = 0,
    /** 会话类型：DIRECT / GROUP / CHANNEL / SECRET（密聊：独立 1:1，与普通私聊分开） */
    val chatType: String = "DIRECT"
)

object ChatType {
    const val DIRECT = "DIRECT"
    const val GROUP = "GROUP"
    const val CHANNEL = "CHANNEL"
    /** 钉钉式密聊：仅 1:1，独立会话，双方同步；不得用于群。 */
    const val SECRET = "SECRET"
}

@Serializable
data class UpdateChatSettingsRequest(
    val pinned: Boolean? = null,
    val notificationsMuted: Boolean? = null,
    val archived: Boolean? = null,
    val markedUnread: Boolean? = null
)

@Serializable
data class ChatSettingsResponse(
    val chatId: String, val pinnedAt: Long, val notificationsMuted: Boolean,
    val archived: Boolean, val markedUnread: Boolean, val updatedAt: Long
)

@Serializable
data class MessageReactionResponse(val userId: String, val emoji: String, val reactedAt: Long)

@Serializable
data class PinnedMessageResponse(
    val chatId: String,
    val messageId: String,
    val pinnedBy: String,
    val pinnedAt: Long
)

@Serializable
data class PinnedMessagesListResponse(
    val chatId: String,
    val pins: List<PinnedMessageResponse> = emptyList()
)

@Serializable
data class TogglePinResponse(
    val status: String = "ok",
    val pinned: Boolean = false,
    val pins: List<PinnedMessageResponse> = emptyList()
)

@Serializable
data class StarredMessageReference(
    val messageId: String,
    val chatId: String,
    val starredAt: Long,
)

@Serializable
data class MessageResponse(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String = "TEXT",
    val timestamp: Long,
    val status: String = "SENT",
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val reactions: List<MessageReactionResponse> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
)

@Serializable
data class UpdateDisappearingMessagesRequest(val seconds: Int = 0)

@Serializable
data class DisappearingMessagesResponse(
    val chatId: String,
    val seconds: Int,
    val updatedAt: Long
)

/** 多设备 DELETE/REVOKE/EDIT 变更回放条目 */
@Serializable
data class MessageMutationResponse(
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
data class UnreadWindowResponse(
    val messageIds: List<String>,
    val totalCount: Int,
    val truncated: Boolean
)

@Serializable
data class AttachmentUploadResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val expiresAt: Long
)

@Serializable
data class AttachmentUploadSessionRequest(
    val chatId: String,
    val messageId: String,
    val cipherSha256: String,
    val cipherSize: Long
)

@Serializable
data class AttachmentUploadStatusResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val uploadedBytes: Long,
    val status: String,
    val expiresAt: Long,
    val complete: Boolean
)

@Serializable
data class UpdateMessageReactionRequest(val emoji: String)