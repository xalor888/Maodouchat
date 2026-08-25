package com.maodouchat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Chat(
    val id: String,
    val participants: List<User> = emptyList(),
    val lastMessage: String = "",
    val lastMessageType: MessageType = MessageType.TEXT,
    val lastMessageTime: Long = System.currentTimeMillis(),
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
    /** 广播频道（单向一对多）。 */
    val isChannel: Boolean get() = chatType == "CHANNEL"
    /** 钉钉式密聊：独立 1:1 会话，与同人普通私聊分开。 */
    val isSecret: Boolean get() = chatType == "SECRET"
}
