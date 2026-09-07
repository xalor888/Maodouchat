package com.maodouchat.server.plugins

internal fun wsRestrictionMessage(until: Long, action: String): String =
    restrictionMessage(until, action)

@kotlinx.serialization.Serializable
internal data class PostDeletedPayload(val postId: String)

@kotlinx.serialization.Serializable
internal data class UserStatusPayload(
    val userId: String,
    val isOnline: Boolean,
    val lastSeen: Long = 0,
    val onlineRevoked: Boolean = false,
    val statusRevoked: Boolean = false
)

@kotlinx.serialization.Serializable
internal data class OutgoingSignalingPayload(
    val toUserId: String,
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

@kotlinx.serialization.Serializable
internal data class IncomingSignalingPayload(
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

@kotlinx.serialization.Serializable
internal data class TypingPayload(val userId: String, val chatId: String, val isTyping: Boolean)
