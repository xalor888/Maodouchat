package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ChatDto

/**
 * Pure merge of a getChats DTO with in-memory / Room local state.
 * Keeps unread, settings and marked-unread races out of ChatListViewModel.
 */
object ChatListRemoteMergePolicy {

    fun mergeRemoteChat(
        dto: ChatDto,
        local: Chat?,
        currentUserId: String,
        isActiveChat: Boolean,
    ): Chat {
        val participants = dto.participants.map {
            User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status)
        }
        val mergedUnread = ChatListUnreadPolicy.mergeUnreadCount(
            serverUnread = dto.unreadCount,
            localUnread = local?.unreadCount,
            isActiveChat = isActiveChat,
            localMarkedUnread = local?.markedUnread == true,
            serverLastMessageTime = dto.lastMessageTime,
            localLastMessageTime = local?.lastMessageTime ?: 0L,
        )
        val serverSettings = ChatListSettingsMergePolicy.SettingsSnapshot(
            pinnedAt = dto.pinnedAt,
            notificationsMuted = dto.notificationsMuted,
            archived = dto.archived,
            markedUnread = dto.markedUnread,
            settingsUpdatedAt = dto.settingsUpdatedAt,
        )
        val localSettings = local?.let {
            ChatListSettingsMergePolicy.SettingsSnapshot(
                pinnedAt = it.pinnedAt,
                notificationsMuted = it.notificationsMuted,
                archived = it.archived,
                markedUnread = it.markedUnread,
                settingsUpdatedAt = it.settingsUpdatedAt,
            )
        }
        val mergedSettings = ChatListSettingsMergePolicy.merge(serverSettings, localSettings)
        val mergedMarked = if (isActiveChat) {
            false
        } else {
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = mergedSettings.markedUnread,
                localMarked = local?.markedUnread,
                isActiveChat = false,
            )
        }
        return Chat(
            id = dto.id,
            participants = if (dto.isGroup) {
                participants
            } else {
                participants.filter { it.id != currentUserId }.ifEmpty { participants }
            },
            lastMessage = dto.lastMessage,
            lastMessageType = MessageType.fromWire(dto.lastMessageType),
            lastMessageTime = dto.lastMessageTime,
            unreadCount = mergedUnread,
            isGroup = dto.isGroup,
            chatType = dto.chatType,
            groupName = dto.groupName,
            groupAnnouncement = dto.groupAnnouncement,
            groupAvatar = dto.groupAvatar,
            memberRevision = dto.memberRevision,
            pinnedAt = mergedSettings.pinnedAt,
            notificationsMuted = mergedSettings.notificationsMuted,
            archived = mergedSettings.archived,
            markedUnread = mergedMarked,
            settingsUpdatedAt = mergedSettings.settingsUpdatedAt,
            disappearingMessageSeconds = dto.disappearingMessageSeconds,
        )
    }

    /** Drop chats the user already left this VM session (leave may not have propagated yet). */
    fun filterDeleted(chats: List<Chat>, deletedChatIds: Set<String>): List<Chat> =
        chats.filterNot { deletedChatIds.contains(it.id) }

    /** Local rows absent from the filtered server snapshot are stale and must be cleaned. */
    fun staleChatIds(localIds: Iterable<String>, serverChatIds: Set<String>): List<String> =
        localIds.filterNot(serverChatIds::contains)

    /**
     * Silent reconnect/foreground must not toast 429 over a populated list;
     * visible refresh may surface a failure message (or keep prior error when rate-limited).
     */
    fun nextErrorOnFailure(
        showLoading: Boolean,
        rateLimited: Boolean,
        currentError: String?,
        failureMessage: String?,
        fallback: String,
    ): String? = when {
        !showLoading || rateLimited -> currentError
        else -> failureMessage?.takeIf { it.isNotBlank() } ?: fallback
    }
}
