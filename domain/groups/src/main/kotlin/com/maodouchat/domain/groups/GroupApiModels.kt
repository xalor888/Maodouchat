package com.maodouchat.domain.groups

import kotlinx.serialization.Serializable

@Serializable
data class GroupMembersRequest(val participantIds: List<String>)

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
data class CreateGroupInviteRequest(val rotate: Boolean = false, val expiresInSeconds: Long = 7L * 24L * 60L * 60L, val maxUses: Int = 100)

@Serializable
data class JoinGroupInviteRequest(val token: String)
