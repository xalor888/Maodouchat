package com.maodouchat.server.model

import kotlinx.serialization.Serializable

/**
 * 9.3xx：群邀请同意流程 DTO——成员被拉入群前必须由本人接受（PENDING → ACCEPTED/DECLINED）。
 */
@Serializable
data class GroupInvitationDto(
    val id: String,
    val chatId: String,
    val userId: String,
    val inviterId: String,
    val inviterName: String = "",
    val chatName: String = "",
    val chatAvatar: String? = null,
    val chatType: String = "GROUP",
    val memberCount: Int = 0,
    val status: String = "PENDING",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** 9.3xx：WS/FCM 群邀请事件负载（CREATED / ACCEPTED / DECLINED / CANCELLED）。 */
@Serializable
data class GroupInviteEventPayload(
    val action: String,
    val invite: GroupInvitationDto
)

