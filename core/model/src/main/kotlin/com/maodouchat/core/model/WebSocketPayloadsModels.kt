package com.maodouchat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WsMessage(val type: String, val payload: String)

@Serializable
data class TypingPayload(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
data class AdminBroadcastPayload(
    val title: String = "System",
    val text: String = "",
    val actorId: String = "",
    val ts: Long = 0L
)

@Serializable
data class IncomingUserStatus(
    val userId: String,
    val isOnline: Boolean,
    val lastSeen: Long = 0,
    val onlineRevoked: Boolean = false,
    val statusRevoked: Boolean = false
)

@Serializable
data class IncomingSignaling(
    val fromUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false,
    val epoch: Long = 0,
    val sequence: Long = 0,
    val idempotencyKey: String = "",
)

@Serializable
data class IncomingServerError(
    val error: String = "",
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
)

@Serializable
data class IncomingPostDeleted(val postId: String)

@Serializable
data class IncomingPinnedMessagesUpdated(
    val chatId: String,
    val actorId: String = "",
    val pins: List<PinnedMessageDto> = emptyList()
)

@Serializable
data class IncomingDisappearingMessagesUpdated(
    val chatId: String,
    val seconds: Int = 0,
    val updatedAt: Long = 0
)

@Serializable
data class IncomingTyping(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
data class IncomingFriendRequestEvent(
    val action: String,
    val request: FriendRequestDto
)

@Serializable
data class IncomingGroupInviteEvent(
    val action: String,
    val invite: GroupInvitationDto
)

@Serializable
data class IncomingGroupRevisionChanged(
    val chatId: String,
    val memberRevision: Long,
    val reason: String,
    val actorId: String? = null,
    val targetUserId: String? = null
)

@Serializable
data class PinnedMessageDto(
    val chatId: String,
    val messageId: String,
    val pinnedBy: String,
    val pinnedAt: Long
)
