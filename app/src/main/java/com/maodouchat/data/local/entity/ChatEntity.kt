package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User

@Entity(
    tableName = "chats",
    indices = [
        Index("archived"),
        Index("lastMessageTime"),
        // B7: 仅追加索引，与 MIGRATION_27_28 保持一致，保证新建库与迁移库 schema 等价
        Index(value = ["archived", "pinnedAt", "lastMessageTime"])
    ]
)
data class ChatEntity(
    @PrimaryKey
    val id: String,
    val lastMessage: String = "",
    val lastMessageType: String = MessageType.TEXT.name,
    val lastMessageTime: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    /** 会话类型：DIRECT / GROUP / CHANNEL（广播频道，单向一对多）。 */
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
    val disappearingMessageSeconds: Int = 0,
    val participantIds: String = "" // 逗号分隔的用户 ID
)

/**
 * 将 Entity 转为领域模型
 *
 * @param participantsMap 用户 ID → User 映射，由 Repository 查询后传入
 *
 * Missing User rows must not drop participant ids: outbox encrypt, group member counts,
 * and 1:1 peer resolve only need a stable id. Stub a minimal [User] when the map has no profile.
 */
fun ChatEntity.toDomain(participantsMap: Map<String, User> = emptyMap()): Chat {
    val ids = participantIds.split(",").filter { it.isNotBlank() }
    val participants = ids.map { id ->
        participantsMap[id] ?: User(id = id, name = "")
    }
    return Chat(
        id = id,
        participants = participants,
        lastMessage = lastMessage,
        lastMessageType = MessageType.fromWire(lastMessageType),
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        isGroup = isGroup,
        chatType = chatType,
        groupName = groupName,
        groupAnnouncement = groupAnnouncement,
        groupAvatar = groupAvatar,
        memberRevision = memberRevision,
        pinnedAt = pinnedAt,
        notificationsMuted = notificationsMuted,
        archived = archived,
        markedUnread = markedUnread,
        settingsUpdatedAt = settingsUpdatedAt,
        disappearingMessageSeconds = disappearingMessageSeconds
    )
}

fun Chat.toEntity(): ChatEntity = ChatEntity(
    id = id,
    lastMessage = lastMessage,
    lastMessageType = lastMessageType.name,
    lastMessageTime = lastMessageTime,
    unreadCount = unreadCount,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    groupAnnouncement = groupAnnouncement,
    groupAvatar = groupAvatar,
    memberRevision = memberRevision,
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    settingsUpdatedAt = settingsUpdatedAt,
    disappearingMessageSeconds = disappearingMessageSeconds,
    participantIds = participants.joinToString(",") { it.id }
)
