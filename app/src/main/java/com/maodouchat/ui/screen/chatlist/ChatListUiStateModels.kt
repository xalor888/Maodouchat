package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.ArchiveSuggestion
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.ChatFolderPolicy
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.core.model.UnreadPriorityPolicy

data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val searchQuery: String = "",
    val messageMatchedChatIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Non-blocking banner when realtime WS is down (cleared on Connected). */
    val realtimeBanner: String? = null,
    val ownerTransferRequiredChatId: String? = null,
    val selectedTab: Int = 0,
    val missedCalls: List<com.maodouchat.core.model.MissedCall> = emptyList(),
    val drafts: Map<String, ChatDraftEntity> = emptyMap(),
    /** 1.146：各会话待发送的本地定时消息数（chatId -> count） */
    val scheduledByChat: Map<String, Int> = emptyMap(),
    val showArchived: Boolean = false,
    /** 本地会话文件夹；null/all 表示全部 */
    val folders: List<ChatFolder> = emptyList(),
    val selectedFolderId: String? = null,
    /** 未读智能优先（本地排序，默认开） */
    val unreadPriorityEnabled: Boolean = true,
    /** 本地 PIN 锁定的会话 id */
    val lockedChatIds: Set<String> = emptySet(),
    /** 密聊会话 id（chatType=SECRET，双方同步的独立 1:1） */
    val secretChatIds: Set<String> = emptySet(),
    /** 从列表发起密聊成功后打开新会话 */
    val createdSecretChatId: String? = null,
    /** 正在删除中的会话 id，防止双击删除并发重复清理本地数据 */
    val deletingChatIds: Set<String> = emptySet(),
    /** 服务端活跃公告（未读 + 生效窗口内，已按优先级排序） */
    val activeAnnouncements: List<com.maodouchat.core.model.AnnouncementData> = emptyList(),
    /** 8.47：智能归档建议（纯本地启发式；采纳走现有归档流程） */
    val archiveSuggestions: List<ArchiveSuggestion> = emptyList(),
    /** 1.103：对端正在输入（chatId -> userId，3s 过期） */
    val typingByChat: Map<String, String> = emptyMap(),
    /** 1.165：身份密钥已变更（CHANGED）的对端 userId（本地 identity_trust 聚合，会话列表警告）。 */
    val identityChangedUserIds: Set<String> = emptySet(),
    /** 1.368：多选模式（批量置顶/已读/删除，对标 TG/微信会话列表长按多选） */
    val selectionMode: Boolean = false,
    /** 1.368：多选模式下已勾选的会话 id */
    val selectedChatIds: Set<String> = emptySet(),
    /** 本机最近一条可见消息的回执（chatId → 自己发出时才有）。不进 Room schema。 */
    val receiptsByChat: Map<String, Receipt> = emptyMap(),
) {
    val filteredChats: List<Chat>
        get() {
            val visible = chats.filter { it.archived == showArchived }
            val folderFiltered = when {
                selectedFolderId.isNullOrBlank() ->
                    visible.filter { !com.maodouchat.core.session.SecretChatPolicy.excludeFromAllChats(it.isSecret) }
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

    val unreadChatCount: Int
        get() = UnreadPriorityPolicy.countUnreadChats(
            unreadCounts = chats.filter { !it.archived && !it.isSecret }.map { it.unreadCount },
            markedUnreadFlags = chats.filter { !it.archived && !it.isSecret }.map { it.markedUnread }
        )

    val showUnreadPriorityHint: Boolean
        get() = UnreadPriorityPolicy.shouldShowHint(
            enabled = unreadPriorityEnabled,
            totalUnreadChats = unreadChatCount,
            isSearching = searchQuery.isNotBlank()
        )

    fun unreadInFolder(folderId: String): Int {
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
            return unreadChatCount
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
