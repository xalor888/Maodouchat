package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.ArchiveSuggestion
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.Receipt
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.core.model.Chat
import com.maodouchat.domain.conversation.ChatDraft
import com.maodouchat.domain.conversation.ChatListVisibleChats

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
        get() = ChatListVisibleChats.filterVisibleChats(
            chats = chats,
            showArchived = showArchived,
            selectedFolderId = selectedFolderId,
            folders = folders,
            secretChatIds = secretChatIds,
            lockedChatIds = lockedChatIds,
            searchQuery = searchQuery,
            messageMatchedChatIds = messageMatchedChatIds,
            drafts = drafts.mapValues { (_, draft) -> ChatDraft(draft.chatId, draft.text, draft.updatedAt) },
            unreadPriorityEnabled = unreadPriorityEnabled
        )

    val unreadChatCount: Int
        get() = ChatListVisibleChats.unreadChatCount(chats)

    val showUnreadPriorityHint: Boolean
        get() = ChatListVisibleChats.showUnreadPriorityHint(unreadPriorityEnabled, chats, searchQuery)

    fun unreadInFolder(folderId: String): Int =
        ChatListVisibleChats.unreadInFolder(chats, folders, secretChatIds, lockedChatIds, folderId)
}
