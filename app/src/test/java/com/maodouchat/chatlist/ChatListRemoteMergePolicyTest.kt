package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ChatDto
import com.maodouchat.network.UserDto
import com.maodouchat.ui.screen.chatlist.ChatListRemoteMergePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListRemoteMergePolicyTest {

    @Test
    fun mergesUnreadAndSettingsPreferringNewerLocal() {
        val dto = ChatDto(
            id = "c1",
            participants = listOf(
                UserDto(id = "me", name = "Me"),
                UserDto(id = "u2", name = "Other"),
            ),
            lastMessage = "hi",
            lastMessageType = "TEXT",
            lastMessageTime = 200,
            unreadCount = 1,
            pinnedAt = 0,
            notificationsMuted = false,
            archived = false,
            markedUnread = false,
            settingsUpdatedAt = 100,
        )
        val local = Chat(
            id = "c1",
            unreadCount = 0,
            lastMessageTime = 200,
            pinnedAt = 50,
            notificationsMuted = true,
            archived = true,
            markedUnread = false,
            settingsUpdatedAt = 150,
        )

        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = local,
            currentUserId = "me",
            isActiveChat = false,
        )

        assertEquals(0, merged.unreadCount)
        assertEquals(50, merged.pinnedAt)
        assertTrue(merged.notificationsMuted)
        assertTrue(merged.archived)
        assertFalse(merged.markedUnread)
        assertEquals(150, merged.settingsUpdatedAt)
        assertEquals(listOf("u2"), merged.participants.map { it.id })
        assertEquals(MessageType.TEXT, merged.lastMessageType)
    }

    @Test
    fun keepsLocalMarkedUnreadWhenOptimisticAheadOfServer() {
        val dto = ChatDto(
            id = "c1",
            unreadCount = 0,
            markedUnread = false,
            settingsUpdatedAt = 100,
            lastMessageTime = 10,
        )
        val local = Chat(
            id = "c1",
            unreadCount = 0,
            markedUnread = true,
            settingsUpdatedAt = 200,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = local,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertTrue(merged.markedUnread)
        assertEquals(200, merged.settingsUpdatedAt)
    }

    @Test
    fun activeChatForcesZeroUnreadAndClearsMarked() {
        val dto = ChatDto(
            id = "c1",
            unreadCount = 5,
            markedUnread = true,
            lastMessageTime = 10,
        )
        val local = Chat(id = "c1", unreadCount = 3, markedUnread = true, lastMessageTime = 10)

        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = local,
            currentUserId = "me",
            isActiveChat = true,
        )

        assertEquals(0, merged.unreadCount)
        assertFalse(merged.markedUnread)
    }

    @Test
    fun filterDeletedAndStaleIds() {
        val chats = listOf(Chat(id = "a"), Chat(id = "b"), Chat(id = "c"))
        val filtered = ChatListRemoteMergePolicy.filterDeleted(chats, setOf("b"))
        assertEquals(listOf("a", "c"), filtered.map { it.id })

        val stale = ChatListRemoteMergePolicy.staleChatIds(
            localIds = listOf("a", "b", "ghost"),
            serverChatIds = setOf("a", "c"),
        )
        assertEquals(listOf("b", "ghost"), stale)
    }

    @Test
    fun nextErrorOnFailureKeepsPriorOnSilentOrRateLimit() {
        assertEquals(
            "old",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = false,
                rateLimited = false,
                currentError = "old",
                failureMessage = "new",
                fallback = "fallback",
            ),
        )
        assertEquals(
            "old",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = true,
                rateLimited = true,
                currentError = "old",
                failureMessage = "new",
                fallback = "fallback",
            ),
        )
        assertEquals(
            "new",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = true,
                rateLimited = false,
                currentError = "old",
                failureMessage = "new",
                fallback = "fallback",
            ),
        )
        assertEquals(
            "fallback",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = true,
                rateLimited = false,
                currentError = null,
                failureMessage = "  ",
                fallback = "fallback",
            ),
        )
        assertNull(
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = false,
                rateLimited = false,
                currentError = null,
                failureMessage = "new",
                fallback = "fallback",
            ),
        )
    }
}
