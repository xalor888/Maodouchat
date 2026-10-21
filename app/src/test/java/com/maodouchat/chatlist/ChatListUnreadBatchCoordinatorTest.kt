package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.session.Snapshot
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import com.maodouchat.ui.screen.chatlist.ChatListUnreadBatchCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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
        val room = mutableMapOf(
            "c1" to ordinary,
            "c2" to secret,
            "c3" to archived,
        )
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<Triple<String, String, Long?>>()
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val coordinator = ChatListUnreadBatchCoordinator(
            scope = this,
            uiState = uiState,
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
        val receipts = mutableListOf<String>()
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val coordinator = ChatListUnreadBatchCoordinator(
            scope = this,
            uiState = uiState,
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

    @Test
    fun markAllReadRoomWriteFailureStillCleansTrayAndReceipts() = runTest(dispatcher) {
        // Room 写入失败被吞：乐观清零不回滚，tray 清理与已读回执照常走完。
        val ordinary = Chat(id = "c1", unreadCount = 2)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(ordinary)))
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            withOwnerRoomWrite = { _, _ -> throw RuntimeException("room down") },
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, uiState.value.chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertEquals(listOf("c1"), cancelled)
        assertEquals(listOf("c1"), receipts)
    }

    @Test
    fun markAllReadStaleSessionSkipsPersistenceButKeepsOptimisticZero() = runTest(dispatcher) {
        // 会话过期：持久化整段跳过，已乐观清零的 UI 不回滚。
        val ordinary = Chat(id = "c1", unreadCount = 2)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(ordinary)))
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            isOwnerSessionCurrent = { false },
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, uiState.value.chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun markAllReadCancellationAbortsRemainingChats() = runTest(dispatcher) {
        // 取消异常不被吞：首个会话抛 CancellationException 后整批中止，后续会话的 tray 清理也不跑。
        val uiState = MutableStateFlow(
            ChatListUiState(
                chats = listOf(
                    Chat(id = "c1", unreadCount = 2),
                    Chat(id = "c2", unreadCount = 1),
                )
            )
        )
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            withOwnerRoomWrite = { _, _ -> throw CancellationException("gone") },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, uiState.value.chats.first { it.id == "c1" }.unreadCount)
        assertEquals(0, uiState.value.chats.first { it.id == "c2" }.unreadCount)
        assertTrue(cancelled.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun markAllReadTwiceIsIdempotent() = runTest(dispatcher) {
        val ordinary = Chat(id = "c1", unreadCount = 2)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(ordinary)))
        val cachedWrites = mutableListOf<Chat>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()
        val writesAfterFirst = cachedWrites.size
        val receiptsAfterFirst = receipts.size

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(1, receiptsAfterFirst)
        assertEquals(writesAfterFirst, cachedWrites.size)
        assertEquals(receiptsAfterFirst, receipts.size)
    }

    @Test
    fun batchMarkReadSelectedEmptySelectionIsNoop() = runTest(dispatcher) {
        val a = Chat(id = "a", unreadCount = 2)
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(a), selectedChatIds = emptySet())
        )
        val cachedWrites = mutableListOf<Chat>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
        )

        coordinator.batchMarkReadSelected()
        advanceUntilIdle()

        assertEquals(2, uiState.value.chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    private fun kotlinx.coroutines.CoroutineScope.buildCoordinator(
        uiState: MutableStateFlow<ChatListUiState>,
        isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean = { true },
        withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean =
            { _, block ->
                block()
                true
            },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        enqueueReadReceipt: suspend (String, String, Long?) -> Unit = { _, _, _ -> },
        cancelMessageNotification: (String) -> Unit = {},
    ): ChatListUnreadBatchCoordinator {
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        return ChatListUnreadBatchCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = isOwnerSessionCurrent,
            withOwnerRoomWrite = withOwnerRoomWrite,
            getCachedChat = { null },
            cacheChats = cacheChats,
            getLatestIncomingMessage = { chatId, _ ->
                Message(id = "m-$chatId", chatId = chatId, senderId = "peer", content = "hi")
            },
            enqueueReadReceipt = enqueueReadReceipt,
            cancelMessageNotification = cancelMessageNotification,
        )
    }
}
