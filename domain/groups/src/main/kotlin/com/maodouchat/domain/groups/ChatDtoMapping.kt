package com.maodouchat.domain.groups

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User

fun ChatDto.toDomainChat(): Chat = Chat(
    id = id,
    participants = participants.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) },
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
    disappearingMessageSeconds = disappearingMessageSeconds
)
