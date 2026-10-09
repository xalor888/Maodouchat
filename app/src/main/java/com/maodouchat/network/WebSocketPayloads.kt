package com.maodouchat.network

import kotlinx.serialization.Serializable

internal data class ResolvedUserVisibility(
    val isOnline: Boolean,
    val status: String,
    val lastSeen: Long
)

internal fun resolveUserVisibility(
    currentIsOnline: Boolean,
    currentStatus: String,
    currentLastSeen: Long,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean
): ResolvedUserVisibility = ResolvedUserVisibility(
    isOnline = when {
        onlineRevoked -> false
        statusRevoked -> currentIsOnline
        else -> eventIsOnline
    },
    status = if (statusRevoked) "" else currentStatus,
    lastSeen = when {
        onlineRevoked -> 0L
        statusRevoked -> currentLastSeen
        else -> eventLastSeen
    }
)

internal fun isNonRecoverableWebSocketNetworkError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException }

@Serializable
internal data class WsMessage(val type: String, val payload: String)

@Serializable
internal data class TypingPayload(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
internal data class AdminBroadcastPayload(
    val title: String = "System",
    val text: String = "",
    val actorId: String = "",
    val ts: Long = 0L
)

@Serializable
internal data class IncomingUserStatus(
    val userId: String,
    val isOnline: Boolean,
    val lastSeen: Long = 0,
    val onlineRevoked: Boolean = false,
    val statusRevoked: Boolean = false
)

@Serializable
internal data class IncomingSignaling(
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
internal data class IncomingServerError(
    val error: String = "",
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
)

@Serializable
internal data class IncomingPostDeleted(val postId: String)

@Serializable
internal data class IncomingPinnedMessagesUpdated(
    val chatId: String,
    val actorId: String = "",
    val pins: List<PinnedMessageDto> = emptyList()
)

@Serializable
internal data class IncomingDisappearingMessagesUpdated(
    val chatId: String,
    val seconds: Int = 0,
    val updatedAt: Long = 0
)

@Serializable
internal data class IncomingTyping(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
internal data class IncomingFriendRequestEvent(
    val action: String,
    val request: FriendRequestDto
)

@Serializable
internal data class IncomingGroupInviteEvent(
    val action: String,
    val invite: GroupInvitationDto
)

@Serializable
internal data class IncomingGroupRevisionChanged(
    val chatId: String,
    val memberRevision: Long,
    val reason: String,
    val actorId: String? = null,
    val targetUserId: String? = null
)
