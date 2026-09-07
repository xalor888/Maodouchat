package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import com.maodouchat.ui.screen.chatlist.ChatListUnreadBatchCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListUnreadBatchCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun markAllUnreadChatsReadZerosUiCachesAndEnqueuesOrdinaryReceipt() = runTest(dispatcher) {
        val ordinary = Chat(id = "c1", unreadCount = 2)
        val secret = Chat(id = "c2", unreadCount = 1, chatType = "SECRET")
        val archived = Chat(id = "c3", unreadCount = 3, archived = true)
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(ordinary, secret, archived))
        )
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        val room = mutableMapOf(
            "c1" to ordinary,
            "c2" to secret,
            "c3" to archived,
        )
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<Triple<String, String, Long?>>()
        val coordinator = ChatListUnreadBatchCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = { true },
            withOwnerRoomWrite = { _, block ->
                block()
                true
            },
            getCachedChat = { id -> room[id] },
            cacheChats = { list ->
                cachedWrites += list
                list.forEach { room[it.id] = it }
            },
            getLatestIncomingMessage = { chatId, _ ->
                Message(id = "m-$chatId", chatId = chatId, senderId = "peer", content = "hi")
            },
            enqueueReadReceipt = { cid, mid, rev -> receipts += Triple(cid, mid, rev) },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, uiState.value.chats.first { it.id == "c1" }.unreadCount)
        assertEquals(0, uiState.value.chats.first { it.id == "c2" }.unreadCount)
        assertEquals(3, uiState.value.chats.first { it.id == "c3" }.unreadCount)
        assertTrue(cancelled.containsAll(listOf("c1", "c2")))
        assertEquals(1, receipts.size)
        assertEquals("c1", receipts.single().first)
        assertEquals("m-c1", receipts.single().second)
        assertEquals(null, receipts.single().third)
        assertTrue(cachedWrites.any { it.id == "c1" && it.unreadCount == 0 })
        assertTrue(cachedWrites.any { it.id == "c2" && it.unreadCount == 0 })
    }

    @Test
    fun batchMarkReadSelectedOnlyTouchesSelected() = runTest(dispatcher) {
        val a = Chat(id = "a", unreadCount = 2)
        val b = Chat(id = "b", unreadCount = 4)
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(a, b), selectedChatIds = setOf("a"))
        )
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        val receipts = mutableListOf<String>()
        val coordinator = ChatListUnreadBatchCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = { true },
            withOwnerRoomWrite = { _, block ->
                block()
                true
            },
            getCachedChat = { null },
            cacheChats = {},
            getLatestIncomingMessage = { chatId, _ ->
                Message(id = "m-$chatId", chatId = chatId, senderId = "peer", content = "hi")
            },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = {},
        )

        coordinator.batchMarkReadSelected()
        advanceUntilIdle()

        assertEquals(0, uiState.value.chats.first { it.id == "a" }.unreadCount)
        assertEquals(4, uiState.value.chats.first { it.id == "b" }.unreadCount)
        assertEquals(listOf("a"), receipts)
    }
}
