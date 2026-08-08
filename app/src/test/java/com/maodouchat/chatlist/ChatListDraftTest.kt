package com.maodouchat.chatlist

import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import com.maodouchat.ui.screen.chatlist.requiresGroupOwnershipTransfer
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListDraftTest {
    @Test
    fun ownerTransferErrorUsesDedicatedRecoveryFlow() {
        assertTrue(
            requiresGroupOwnershipTransfer(
                ApiException(
                    kind = ApiFailureKind.HTTP,
                    statusCode = 409,
                    serverMessage = "localized message",
                    serverCode = "GROUP_OWNER_TRANSFER_REQUIRED"
                )
            )
        )
        assertFalse(
            requiresGroupOwnershipTransfer(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverCode = "OTHER_CONFLICT")
            )
        )
    }

    @Test
    fun newerDraftMovesConversationAheadOfOlderMessage() {
        val state = ChatListUiState(
            chats = listOf(
                Chat(id = "recent-message", lastMessage = "new", lastMessageTime = 200L),
                Chat(id = "draft-chat", lastMessage = "old", lastMessageTime = 100L)
            ),
            drafts = mapOf(
                "draft-chat" to ChatDraftEntity("owner", "draft-chat", "unfinished", 300L)
            )
        )

        assertEquals(listOf("draft-chat", "recent-message"), state.filteredChats.map(Chat::id))
    }

    @Test
    fun searchMatchesEncryptedLocalDraftText() {
        val state = ChatListUiState(
            chats = listOf(Chat(id = "draft-chat", lastMessage = "unrelated", lastMessageTime = 100L)),
            searchQuery = "发布计划",
            drafts = mapOf(
                "draft-chat" to ChatDraftEntity("owner", "draft-chat", "明天讨论发布计划", 200L)
            )
        )

        assertEquals(listOf("draft-chat"), state.filteredChats.map(Chat::id))
    }

    @Test
    fun pinnedConversationStaysAheadOfNewerMessage() {
        val state = ChatListUiState(
            chats = listOf(
                Chat(id = "newer", lastMessageTime = 500L),
                Chat(id = "pinned", lastMessageTime = 100L, pinnedAt = 200L)
            )
        )

        assertEquals(listOf("pinned", "newer"), state.filteredChats.map(Chat::id))
    }

    @Test
    fun archivedConversationsAreSeparatedFromInbox() {
        val chats = listOf(
            Chat(id = "inbox", archived = false),
            Chat(id = "archived", archived = true)
        )

        assertEquals(listOf("inbox"), ChatListUiState(chats = chats).filteredChats.map(Chat::id))
        assertEquals(
            listOf("archived"),
            ChatListUiState(chats = chats, showArchived = true).filteredChats.map(Chat::id)
        )
    }
}
