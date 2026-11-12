package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.ChatFolderPolicy
import com.maodouchat.core.model.UnreadPriorityPolicy
import com.maodouchat.core.session.SecretChatPolicy

// 会话列表可见会话的纯决策：文件夹过滤 + 搜索 + 置顶/未读优先排序；草稿用纯模型 ChatDraft。
object ChatListVisibleChats {

    fun filterVisibleChats(
        chats: List<Chat>,
        showArchived: Boolean,
        selectedFolderId: String?,
        folders: List<ChatFolder>,
        secretChatIds: Set<String>,
        lockedChatIds: Set<String>,
        searchQuery: String,
        messageMatchedChatIds: Set<String>,
        drafts: Map<String, ChatDraft>,
        unreadPriorityEnabled: Boolean
    ): List<Chat> {
        val visible = chats.filter { it.archived == showArchived }
        val folderFiltered = when {
            selectedFolderId.isNullOrBlank() ->
                visible.filter { !SecretChatPolicy.excludeFromAllChats(it.isSecret) }
            selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID ->
                visible.filter { it.isGroup }
            selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID ->
                visible.filter { !it.isGroup && !it.isSecret }
            selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID ->
                visible.filter {
                    !it.isSecret &&
                        ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread)
                }
            selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID ->
                visible.filter { it.isSecret || it.id in secretChatIds }
            selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID ->
                visible.filter { it.id in lockedChatIds && !it.isSecret }
            else -> {
                val folder = folders.firstOrNull { it.id == selectedFolderId }
                if (folder == null) visible.filter { !it.isSecret }
                else {
                    val ids = folder.chatIds.toSet()
                    visible.filter { it.id in ids && !it.isSecret }
                }
            }
        }
        val matched = if (searchQuery.isBlank()) folderFiltered else folderFiltered.filter { chat ->
            val name = chat.groupName
                ?: chat.participants.firstOrNull()?.displayName
                ?: chat.participants.firstOrNull()?.name
                ?: ""
            val nameHit = name.contains(searchQuery, ignoreCase = true) ||
                chat.participants.any { user ->
                    user.displayName.contains(searchQuery, ignoreCase = true) ||
                        user.name.contains(searchQuery, ignoreCase = true) ||
                        (user.nickname?.contains(searchQuery, ignoreCase = true) == true) ||
                        user.email.contains(searchQuery, ignoreCase = true)
                }
            // PIN-locked chats: match by title/participants only; hide body/draft hits.
            if (chat.id in lockedChatIds) return@filter nameHit
            nameHit ||
                chat.lastMessage.contains(searchQuery, ignoreCase = true) ||
                drafts[chat.id]?.text?.contains(searchQuery, ignoreCase = true) == true ||
                chat.id in messageMatchedChatIds
        }
        return matched.sortedWith(
            compareByDescending<Chat> { it.pinnedAt > 0 }
                .thenByDescending { it.pinnedAt }
                .thenByDescending { chat ->
                    if (unreadPriorityEnabled) {
                        UnreadPriorityPolicy.activityScore(
                            lastMessageTime = chat.lastMessageTime,
                            draftUpdatedAt = drafts[chat.id]?.updatedAt ?: 0L,
                            unreadCount = chat.unreadCount,
                            markedUnread = chat.markedUnread,
                            muted = chat.notificationsMuted
                        )
                    } else {
                        maxOf(chat.lastMessageTime, drafts[chat.id]?.updatedAt ?: 0L)
                    }
                }
                // 1.244：时间相近时静音会话排后面（微信式；仅在未读优先关闭时生效）
                .thenBy { if (!unreadPriorityEnabled && it.notificationsMuted) 1 else 0 }
        )
    }

    // 未读会话数：已归档与密聊不计入。
    fun unreadChatCount(chats: List<Chat>): Int =
        UnreadPriorityPolicy.countUnreadChats(
            unreadCounts = chats.filter { !it.archived && !it.isSecret }.map { it.unreadCount },
            markedUnreadFlags = chats.filter { !it.archived && !it.isSecret }.map { it.markedUnread }
        )

    fun showUnreadPriorityHint(
        unreadPriorityEnabled: Boolean,
        chats: List<Chat>,
        searchQuery: String
    ): Boolean =
        UnreadPriorityPolicy.shouldShowHint(
            enabled = unreadPriorityEnabled,
            totalUnreadChats = unreadChatCount(chats),
            isSearching = searchQuery.isNotBlank()
        )

    fun unreadInFolder(
        chats: List<Chat>,
        folders: List<ChatFolder>,
        secretChatIds: Set<String>,
        lockedChatIds: Set<String>,
        folderId: String
    ): Int {
        if (folderId == ChatFolderPolicy.SYSTEM_GROUPS_ID) {
            return UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = chats.filter { !it.archived && it.isGroup }.map { it.unreadCount },
                markedUnreadFlags = chats.filter { !it.archived && it.isGroup }.map { it.markedUnread }
            )
        }
        if (folderId == ChatFolderPolicy.SYSTEM_DIRECT_ID) {
            return UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = chats.filter { !it.archived && !it.isGroup && !it.isSecret }.map { it.unreadCount },
                markedUnreadFlags = chats.filter { !it.archived && !it.isGroup && !it.isSecret }.map { it.markedUnread }
            )
        }
        if (folderId == ChatFolderPolicy.SYSTEM_UNREAD_ID) {
            return unreadChatCount(chats)
        }
        if (folderId == ChatFolderPolicy.SYSTEM_SECRET_ID) {
            val secretVisible = chats.filter { !it.archived && (it.isSecret || it.id in secretChatIds) }
            return UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = secretVisible.map { it.unreadCount },
                markedUnreadFlags = secretVisible.map { it.markedUnread }
            )
        }
        if (folderId == ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            val lockedVisible = chats.filter { !it.archived && it.id in lockedChatIds && !it.isSecret }
            return UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = lockedVisible.map { it.unreadCount },
                markedUnreadFlags = lockedVisible.map { it.markedUnread }
            )
        }
        val folder = folders.firstOrNull { it.id == folderId } ?: return 0
        val unreadMap = chats.filter { !it.isSecret }.associate { it.id to it.unreadCount }
        return ChatFolderPolicy.unreadInFolder(folder, unreadMap)
    }
}
