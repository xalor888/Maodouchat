package com.maodouchat.group

import com.maodouchat.network.GroupMemberDto

data class GroupMemberUi(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
) {
    val displayName: String get() = groupNickname?.takeIf { it.isNotBlank() } ?: name
    val isMuted: Boolean get() = mutedUntil > System.currentTimeMillis()
}

fun GroupMemberDto.toUi(): GroupMemberUi = GroupMemberUi(
    userId = userId,
    name = name,
    avatar = avatar,
    role = role,
    title = title,
    groupNickname = groupNickname,
    joinedAt = joinedAt,
    isOnline = isOnline,
    mutedUntil = mutedUntil
)

data class GroupMembershipSnapshot(
    val chatId: String,
    val memberRevision: Long,
    val members: List<GroupMemberUi>,
    val myRole: String = "MEMBER",
    val myNickname: String = "",
    val groupName: String = "",
    val groupAnnouncement: String = "",
    val groupAvatar: String? = null,
    val isChannel: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
