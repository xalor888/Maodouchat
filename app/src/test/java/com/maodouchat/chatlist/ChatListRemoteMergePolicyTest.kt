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

    @Test
    fun groupChatKeepsAllParticipantsIncludingSelf() {
        val dto = ChatDto(
            id = "g1",
            participants = listOf(
                UserDto(id = "me", name = "Me"),
                UserDto(id = "u2", name = "Other"),
            ),
            isGroup = true,
            unreadCount = 1,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(listOf("me", "u2"), merged.participants.map { it.id })
        assertEquals(1, merged.unreadCount)
    }

    @Test
    fun singleChatWithOnlySelfFallsBackToFullList() {
        val dto = ChatDto(
            id = "c1",
            participants = listOf(UserDto(id = "me", name = "Me")),
            unreadCount = 0,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(listOf("me"), merged.participants.map { it.id })
    }

    @Test
    fun equalSettingsTimestampsPreferServerSnapshot() {
        val dto = ChatDto(
            id = "c1",
            unreadCount = 2,
            lastMessageTime = 10,
            pinnedAt = 10,
            notificationsMuted = true,
            archived = false,
            markedUnread = false,
            settingsUpdatedAt = 100,
        )
        val local = Chat(
            id = "c1",
            unreadCount = 2,
            lastMessageTime = 10,
            pinnedAt = 50,
            notificationsMuted = false,
            archived = true,
            markedUnread = false,
            settingsUpdatedAt = 100,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = local,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(10, merged.pinnedAt)
        assertTrue(merged.notificationsMuted)
        assertFalse(merged.archived)
        assertFalse(merged.markedUnread)
        assertEquals(100, merged.settingsUpdatedAt)
    }

    @Test
    fun nullLocalAcceptsServerUnreadAndSettings() {
        val dto = ChatDto(
            id = "c1",
            participants = listOf(UserDto(id = "u2", name = "Other")),
            unreadCount = 3,
            lastMessageTime = 10,
            pinnedAt = 7,
            notificationsMuted = true,
            archived = true,
            markedUnread = true,
            settingsUpdatedAt = 42,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(3, merged.unreadCount)
        assertEquals(listOf("u2"), merged.participants.map { it.id })
        assertEquals(7, merged.pinnedAt)
        assertTrue(merged.notificationsMuted)
        assertTrue(merged.archived)
        assertTrue(merged.markedUnread)
        assertEquals(42, merged.settingsUpdatedAt)
    }

    @Test
    fun filterDeletedAndStaleEdgeCases() {
        assertEquals(
            emptyList(),
            ChatListRemoteMergePolicy.filterDeleted(emptyList(), setOf("a")),
        )
        val chats = listOf(Chat(id = "a"), Chat(id = "b"))
        assertEquals(
            listOf("a", "b"),
            ChatListRemoteMergePolicy.filterDeleted(chats, emptySet()).map { it.id },
        )
        assertEquals(
            emptyList(),
            ChatListRemoteMergePolicy.staleChatIds(emptyList(), setOf("a")),
        )
        assertEquals(
            listOf("a", "b"),
            ChatListRemoteMergePolicy.staleChatIds(listOf("a", "b"), emptySet()),
        )
    }

    @Test
    fun visibleRefreshWithBlankOrMissingFailureFallsBack() {
        assertEquals(
            "fallback",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = true,
                rateLimited = false,
                currentError = "old",
                failureMessage = "  ",
                fallback = "fallback",
            ),
        )
        assertEquals(
            "fallback",
            ChatListRemoteMergePolicy.nextErrorOnFailure(
                showLoading = true,
                rateLimited = false,
                currentError = "old",
                failureMessage = null,
                fallback = "fallback",
            ),
        )
    }

    @Test
    fun singleChatFiltersSelfFromParticipants() {
        val dto = ChatDto(
            id = "c1",
            participants = listOf(
                UserDto(id = "me", name = "Me"),
                UserDto(id = "u2", name = "Other"),
                UserDto(id = "u3", name = "Third"),
            ),
            unreadCount = 0,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(listOf("u2", "u3"), merged.participants.map { it.id })
    }

    @Test
    fun groupMergePassesThroughGroupMetadata() {
        val dto = ChatDto(
            id = "g1",
            participants = listOf(UserDto(id = "u2", name = "Other")),
            isGroup = true,
            groupName = "Team",
            groupAnnouncement = "Hello",
            groupAvatar = "ava",
            memberRevision = 7,
            chatType = "GROUP",
            disappearingMessageSeconds = 60,
            unreadCount = 0,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals("Team", merged.groupName)
        assertEquals("Hello", merged.groupAnnouncement)
        assertEquals("ava", merged.groupAvatar)
        assertEquals(7, merged.memberRevision)
        assertEquals("GROUP", merged.chatType)
        assertEquals(60, merged.disappearingMessageSeconds)
    }

    @Test
    fun activeChatWithNullLocalForcesZeroUnreadAndClearsMarked() {
        val dto = ChatDto(
            id = "c1",
            unreadCount = 4,
            markedUnread = true,
            lastMessageTime = 10,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = null,
            currentUserId = "me",
            isActiveChat = true,
        )
        assertEquals(0, merged.unreadCount)
        assertFalse(merged.markedUnread)
    }

    @Test
    fun localMarkedUnreadSurvivesNewerServerSettings() {
        // Settings merge prefers the newer server snapshot, but the marked-unread
        // merge keeps the optimistic local toggle when the server still says false.
        val dto = ChatDto(
            id = "c1",
            unreadCount = 0,
            lastMessageTime = 10,
            pinnedAt = 9,
            markedUnread = false,
            settingsUpdatedAt = 200,
        )
        val local = Chat(
            id = "c1",
            unreadCount = 0,
            lastMessageTime = 10,
            pinnedAt = 50,
            markedUnread = true,
            settingsUpdatedAt = 100,
        )
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
            dto = dto,
            local = local,
            currentUserId = "me",
            isActiveChat = false,
        )
        assertEquals(200, merged.settingsUpdatedAt)
        assertEquals(9, merged.pinnedAt)
        assertTrue(merged.markedUnread)
    }

}
