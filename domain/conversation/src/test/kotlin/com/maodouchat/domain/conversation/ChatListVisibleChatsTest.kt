package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.ChatFolderPolicy
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListVisibleChatsTest {

    private fun visible(
        chats: List<Chat>,
        showArchived: Boolean = false,
        selectedFolderId: String? = null,
        folders: List<ChatFolder> = emptyList(),
        secretChatIds: Set<String> = emptySet(),
        lockedChatIds: Set<String> = emptySet(),
        searchQuery: String = "",
        messageMatchedChatIds: Set<String> = emptySet(),
        drafts: Map<String, ChatDraft> = emptyMap(),
        unreadPriorityEnabled: Boolean = true
    ): List<Chat> = ChatListVisibleChats.filterVisibleChats(
        chats = chats,
        showArchived = showArchived,
        selectedFolderId = selectedFolderId,
        folders = folders,
        secretChatIds = secretChatIds,
        lockedChatIds = lockedChatIds,
        searchQuery = searchQuery,
        messageMatchedChatIds = messageMatchedChatIds,
        drafts = drafts,
        unreadPriorityEnabled = unreadPriorityEnabled
    )

    private fun draft(chatId: String, text: String, updatedAt: Long) =
        ChatDraft(chatId = chatId, text = text, updatedAt = updatedAt)

    @Test
    fun newerDraftMovesConversationAheadOfOlderMessage() {
        val chats = listOf(
            Chat(id = "recent-message", lastMessage = "new", lastMessageTime = 200L),
            Chat(id = "draft-chat", lastMessage = "old", lastMessageTime = 100L)
        )
        val drafts = mapOf("draft-chat" to draft("draft-chat", "unfinished", 300L))

        assertEquals(listOf("draft-chat", "recent-message"), visible(chats, drafts = drafts).map(Chat::id))
    }

    @Test
    fun searchMatchesEncryptedLocalDraftText() {
        val chats = listOf(Chat(id = "draft-chat", lastMessage = "unrelated", lastMessageTime = 100L))
        val drafts = mapOf("draft-chat" to draft("draft-chat", "明天讨论发布计划", 200L))

        assertEquals(listOf("draft-chat"), visible(chats, searchQuery = "发布计划", drafts = drafts).map(Chat::id))
    }

    @Test
    fun pinnedConversationStaysAheadOfNewerMessage() {
        val chats = listOf(
            Chat(id = "newer", lastMessageTime = 500L),
            Chat(id = "pinned", lastMessageTime = 100L, pinnedAt = 200L)
        )

        assertEquals(listOf("pinned", "newer"), visible(chats).map(Chat::id))
    }

    @Test
    fun archivedConversationsAreSeparatedFromInbox() {
        val chats = listOf(
            Chat(id = "inbox", archived = false),
            Chat(id = "archived", archived = true)
        )

        assertEquals(listOf("inbox"), visible(chats).map(Chat::id))
        assertEquals(listOf("archived"), visible(chats, showArchived = true).map(Chat::id))
    }

    @Test
    fun olderDraftDoesNotPromoteAheadOfNewerMessage() {
        val chats = listOf(
            Chat(id = "newer", lastMessageTime = 200L),
            Chat(id = "older", lastMessageTime = 100L)
        )
        val drafts = mapOf("older" to draft("older", "stale", 50L))

        assertEquals(listOf("newer", "older"), visible(chats, drafts = drafts).map(Chat::id))
    }

    @Test
    fun lockedChatDraftTextDoesNotMatchSearch() {
        val chats = listOf(Chat(id = "draft-chat", lastMessage = "unrelated", lastMessageTime = 100L))
        val drafts = mapOf("draft-chat" to draft("draft-chat", "明天讨论发布计划", 200L))

        assertEquals(
            emptyList(),
            visible(chats, searchQuery = "发布计划", drafts = drafts, lockedChatIds = setOf("draft-chat")).map(Chat::id)
        )
    }

    @Test
    fun mutedChatSortsAfterSameTimeChatWhenUnreadPriorityOff() {
        val chats = listOf(
            Chat(id = "muted", lastMessageTime = 200L, notificationsMuted = true),
            Chat(id = "plain", lastMessageTime = 200L)
        )

        assertEquals(listOf("plain", "muted"), visible(chats, unreadPriorityEnabled = false).map(Chat::id))
    }

    @Test
    fun largerPinnedAtWinsAmongPinned() {
        val chats = listOf(
            Chat(id = "pin100", lastMessageTime = 900L, pinnedAt = 100L),
            Chat(id = "pin300", lastMessageTime = 100L, pinnedAt = 300L)
        )

        assertEquals(listOf("pin300", "pin100"), visible(chats).map(Chat::id))
    }

    @Test
    fun unreadChatSortsAheadOfNewerReadChatWhenUnreadPriorityOn() {
        val chats = listOf(
            Chat(id = "read-newer", lastMessageTime = 200L),
            Chat(id = "unread-older", lastMessageTime = 100L, unreadCount = 3)
        )

        assertEquals(listOf("unread-older", "read-newer"), visible(chats).map(Chat::id))
        assertEquals(listOf("read-newer", "unread-older"), visible(chats, unreadPriorityEnabled = false).map(Chat::id))
    }

    @Test
    fun groupsFolderShowsOnlyGroups() {
        val chats = listOf(
            Chat(id = "direct", lastMessageTime = 300L),
            Chat(id = "group", isGroup = true, lastMessageTime = 200L)
        )

        assertEquals(
            listOf("group"),
            visible(chats, selectedFolderId = ChatFolderPolicy.SYSTEM_GROUPS_ID).map(Chat::id)
        )
    }

    @Test
    fun directFolderExcludesGroupsAndSecrets() {
        val chats = listOf(
            Chat(id = "direct", lastMessageTime = 300L),
            Chat(id = "group", isGroup = true, lastMessageTime = 200L),
            Chat(id = "secret", chatType = "SECRET", lastMessageTime = 100L)
        )

        assertEquals(
            listOf("direct"),
            visible(chats, selectedFolderId = ChatFolderPolicy.SYSTEM_DIRECT_ID).map(Chat::id)
        )
    }

    @Test
    fun unreadFolderShowsUnreadAndMarkedButNotSecret() {
        val chats = listOf(
            Chat(id = "read", lastMessageTime = 400L),
            Chat(id = "unread", unreadCount = 2, lastMessageTime = 300L),
            Chat(id = "marked", markedUnread = true, lastMessageTime = 200L),
            Chat(id = "secret-unread", chatType = "SECRET", unreadCount = 5, lastMessageTime = 100L)
        )

        // 标未读加权（+10 亿）高于普通未读（+5 亿），marked 排在 unread 前面。
        assertEquals(
            listOf("marked", "unread"),
            visible(chats, selectedFolderId = ChatFolderPolicy.SYSTEM_UNREAD_ID).map(Chat::id)
        )
    }

    @Test
    fun secretFolderShowsSecretsAndSecretIds() {
        val chats = listOf(
            Chat(id = "direct", lastMessageTime = 400L),
            Chat(id = "secret", chatType = "SECRET", lastMessageTime = 300L),
            Chat(id = "secret-id", lastMessageTime = 200L)
        )

        assertEquals(
            listOf("secret", "secret-id"),
            visible(chats, selectedFolderId = ChatFolderPolicy.SYSTEM_SECRET_ID, secretChatIds = setOf("secret-id")).map(Chat::id)
        )
    }

    @Test
    fun lockedFolderShowsLockedNonSecretChats() {
        val chats = listOf(
            Chat(id = "locked", lastMessageTime = 400L),
            Chat(id = "locked-secret", chatType = "SECRET", lastMessageTime = 300L),
            Chat(id = "plain", lastMessageTime = 200L)
        )

        assertEquals(
            listOf("locked"),
            visible(
                chats,
                selectedFolderId = ChatFolderPolicy.SYSTEM_LOCKED_ID,
                lockedChatIds = setOf("locked", "locked-secret")
            ).map(Chat::id)
        )
    }

    @Test
    fun customFolderShowsOnlyItsMembersExcludingSecrets() {
        val folder = ChatFolder(id = "f1", name = "工作", chatIds = listOf("a", "c", "secret-in-folder"))
        val chats = listOf(
            Chat(id = "a", lastMessageTime = 300L),
            Chat(id = "b", lastMessageTime = 200L),
            Chat(id = "c", lastMessageTime = 100L),
            Chat(id = "secret-in-folder", chatType = "SECRET", lastMessageTime = 400L)
        )

        assertEquals(listOf("a", "c"), visible(chats, selectedFolderId = "f1", folders = listOf(folder)).map(Chat::id))
    }

    @Test
    fun unknownFolderFallsBackToNonSecretChats() {
        val chats = listOf(
            Chat(id = "direct", lastMessageTime = 200L),
            Chat(id = "secret", chatType = "SECRET", lastMessageTime = 100L)
        )

        assertEquals(listOf("direct"), visible(chats, selectedFolderId = "no-such-folder").map(Chat::id))
    }

    @Test
    fun allChatsViewExcludesSecrets() {
        val chats = listOf(
            Chat(id = "direct", lastMessageTime = 200L),
            Chat(id = "secret", chatType = "SECRET", lastMessageTime = 100L)
        )

        assertEquals(listOf("direct"), visible(chats).map(Chat::id))
    }

    @Test
    fun searchMatchesMessageMatchedChatIds() {
        val chats = listOf(Chat(id = "hit", lastMessage = "unrelated", lastMessageTime = 100L))

        assertEquals(
            listOf("hit"),
            visible(chats, searchQuery = "关键词", messageMatchedChatIds = setOf("hit")).map(Chat::id)
        )
        assertEquals(emptyList(), visible(chats, searchQuery = "关键词").map(Chat::id))
    }

    @Test
    fun searchMatchesParticipantName() {
        val chats = listOf(
            Chat(
                id = "dm",
                lastMessage = "unrelated",
                lastMessageTime = 100L,
                participants = listOf(User(id = "u1", name = "张三"))
            )
        )

        assertEquals(listOf("dm"), visible(chats, searchQuery = "张三").map(Chat::id))
    }

    @Test
    fun unreadChatCountExcludesArchivedAndSecret() {
        val chats = listOf(
            Chat(id = "unread", unreadCount = 2),
            Chat(id = "marked", markedUnread = true),
            Chat(id = "read"),
            Chat(id = "archived-unread", archived = true, unreadCount = 9),
            Chat(id = "secret-unread", chatType = "SECRET", unreadCount = 9)
        )

        assertEquals(2, ChatListVisibleChats.unreadChatCount(chats))
    }

    @Test
    fun showUnreadPriorityHintNeedsUnreadWithoutSearch() {
        val chats = listOf(Chat(id = "unread", unreadCount = 1))

        assertTrue(ChatListVisibleChats.showUnreadPriorityHint(true, chats, ""))
        assertFalse(ChatListVisibleChats.showUnreadPriorityHint(true, chats, "query"))
        assertFalse(ChatListVisibleChats.showUnreadPriorityHint(false, chats, ""))
        assertFalse(ChatListVisibleChats.showUnreadPriorityHint(true, emptyList(), ""))
    }

    @Test
    fun unreadInFolderCountsPerFolder() {
        val folder = ChatFolder(id = "f1", name = "工作", chatIds = listOf("a", "b"))
        val chats = listOf(
            Chat(id = "a", unreadCount = 2, lastMessageTime = 300L),
            Chat(id = "b", unreadCount = 0, lastMessageTime = 200L),
            Chat(id = "group", isGroup = true, unreadCount = 3, lastMessageTime = 100L),
            Chat(id = "secret", chatType = "SECRET", unreadCount = 5, lastMessageTime = 50L)
        )

        assertEquals(2, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), "f1"))
        assertEquals(1, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), ChatFolderPolicy.SYSTEM_GROUPS_ID))
        assertEquals(1, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), ChatFolderPolicy.SYSTEM_DIRECT_ID))
        assertEquals(2, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), ChatFolderPolicy.SYSTEM_UNREAD_ID))
        assertEquals(1, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), ChatFolderPolicy.SYSTEM_SECRET_ID))
        assertEquals(0, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), ChatFolderPolicy.SYSTEM_LOCKED_ID))
        assertEquals(0, ChatListVisibleChats.unreadInFolder(chats, listOf(folder), emptySet(), emptySet(), "no-such-folder"))
    }
}
