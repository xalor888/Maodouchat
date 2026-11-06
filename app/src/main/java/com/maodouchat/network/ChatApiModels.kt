package com.maodouchat.network

import com.maodouchat.core.model.UserDto
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
data class GroupInviteResponse(val token: String, val payload: String, val chat: ChatDto? = null, val expiresAt: Long = 0, val maxUses: Int = 0, val usedCount: Int = 0, val remainingUses: Int = 0)
