package com.maodouchat.server.model

import kotlinx.serialization.Serializable


@Serializable
data class CreateChatRequest(
    val participantIds: List<String>,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    /** DIRECT / GROUP / CHANNEL / SECRET；null 时由 isGroup 推导。 */
    val chatType: String? = null
)

@Serializable
data class UpdateGroupAnnouncementRequest(val announcement: String)

@Serializable
data class GroupInviteResponse(
    val token: String,
    val payload: String,
    val chat: ChatResponse? = null,
    val expiresAt: Long = 0,
    val maxUses: Int = 0,
    val usedCount: Int = 0,
    val remainingUses: Int = 0
)

@Serializable
data class CreateGroupInviteRequest(
    val rotate: Boolean = false,
    val expiresInSeconds: Long = 7L * 24L * 60L * 60L,
    val maxUses: Int = 100
)

@Serializable
data class GroupAuditLogResponse(
    val id: String,
    val actorId: String,
    val actorName: String,
    val action: String,
    val targetUserId: String? = null,
    val targetUserName: String? = null,
    val createdAt: Long
)

@Serializable
data class JoinGroupInviteRequest(val token: String)

@Serializable
data class WsMessage(val type: String, val payload: String)

@Serializable
data class GroupRevisionChangedPayload(
    val chatId: String,
    val memberRevision: Long,
    val reason: String,
    val actorId: String? = null,
    val targetUserId: String? = null
)
