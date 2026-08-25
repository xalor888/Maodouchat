package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class ChatDto(
    val id: String,
    val participants: List<UserDto> = emptyList(),
    val lastMessage: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    /** 会话类型：DIRECT / GROUP / CHANNEL / SECRET（密聊独立 1:1）。 */
    val chatType: String = if (isGroup) "GROUP" else "DIRECT",
    val groupName: String? = null,
    val groupAnnouncement: String? = null,
    val groupAvatar: String? = null,
    val memberRevision: Long = 0,
    val pinnedAt: Long = 0,
    val notificationsMuted: Boolean = false,
    val archived: Boolean = false,
    val markedUnread: Boolean = false,
    val settingsUpdatedAt: Long = 0,
    val disappearingMessageSeconds: Int = 0
) {
    val isChannel: Boolean get() = chatType == "CHANNEL"
    val isSecret: Boolean get() = chatType == "SECRET"
}

@Serializable
data class UpdateChatSettingsRequest(val pinned: Boolean? = null, val notificationsMuted: Boolean? = null, val archived: Boolean? = null, val markedUnread: Boolean? = null)

@Serializable
data class ChatSettingsResponse(val chatId: String, val pinnedAt: Long, val notificationsMuted: Boolean, val archived: Boolean, val markedUnread: Boolean, val updatedAt: Long)

@Serializable
data class CreateChatRequest(
    val participantIds: List<String>,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    /** DIRECT / GROUP / CHANNEL / SECRET；null 时由 isGroup 推导。 */
    val chatType: String? = null
)

@Serializable
data class GroupMembersRequest(val participantIds: List<String>)

@Serializable
data class GroupMemberDto(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
)

@Serializable
data class UpdateMemberRoleRequest(val role: String)

@Serializable
data class UpdateGroupNicknameRequest(val groupNickname: String)

@Serializable
data class UpdateMemberTitleRequest(val title: String)

@Serializable
data class UpdateMemberMuteRequest(val mutedUntil: Long)

@Serializable
data class UpdateGroupAnnouncementRequest(val announcement: String)

@Serializable
data class GroupInviteResponse(val token: String, val payload: String, val chat: ChatDto? = null, val expiresAt: Long = 0, val maxUses: Int = 0, val usedCount: Int = 0, val remainingUses: Int = 0)

@Serializable
data class CreateGroupInviteRequest(val rotate: Boolean = false, val expiresInSeconds: Long = 7L * 24L * 60L * 60L, val maxUses: Int = 100)

@Serializable
data class GroupAuditLogDto(val id: String, val actorId: String, val actorName: String, val action: String, val targetUserId: String? = null, val targetUserName: String? = null, val createdAt: Long)

@Serializable
data class JoinGroupInviteRequest(val token: String)

@Serializable
data class SenderKeyDistributionTargetRequest(
    val userId: String,
    val deviceId: Int,
    val status: String = "SENT",
    val error: String? = null
)

@Serializable
data class SenderKeyDistributionReportRequest(
    val epoch: Long,
    val messageId: String? = null,
    val targets: List<SenderKeyDistributionTargetRequest>
)

@Serializable
data class SenderKeyDistributionTargetDto(
    val userId: String,
    val deviceId: Int,
    val status: String,
    val error: String? = null,
    val updatedAt: Long
)

@Serializable
data class SenderKeyDistributionStatusDto(
    val chatId: String,
    val epoch: Long,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val targets: List<SenderKeyDistributionTargetDto> = emptyList()
)

