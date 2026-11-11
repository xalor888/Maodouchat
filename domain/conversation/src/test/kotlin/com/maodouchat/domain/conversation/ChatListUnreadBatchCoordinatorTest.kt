package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * 从 app 侧随迁：`ChatListUiState` 经端口替换为本地 chats/selected 持有，
 * `CurrentSession.hasSession()` 经 `hasSession` 端口注入；判定语义与原测试一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListUnreadBatchCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun markAllUnreadChatsReadZerosUiCachesAndEnqueuesOrdinaryReceipt() = runTest(dispatcher) {
        val ordinary = Chat(id = "c1", unreadCount = 2)
        val secret = Chat(id = "c2", unreadCount = 1, chatType = "SECRET")
        val archived = Chat(id = "c3", unreadCount = 3, archived = true)
        var chats = listOf(ordinary, secret, archived)
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
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            getSelectedChatIds = { emptySet() },
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
            hasSession = { true },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, chats.first { it.id == "c1" }.unreadCount)
        assertEquals(0, chats.first { it.id == "c2" }.unreadCount)
        assertEquals(3, chats.first { it.id == "c3" }.unreadCount)
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
        var chats = listOf(a, b)
        val receipts = mutableListOf<String>()
        val coordinator = ChatListUnreadBatchCoordinator(
            scope = this,
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            getSelectedChatIds = { setOf("a") },
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
            hasSession = { true },
        )

        coordinator.batchMarkReadSelected()
        advanceUntilIdle()

        assertEquals(0, chats.first { it.id == "a" }.unreadCount)
        assertEquals(4, chats.first { it.id == "b" }.unreadCount)
        assertEquals(listOf("a"), receipts)
    }

    @Test
    fun markAllReadRoomWriteFailureStillCleansTrayAndReceipts() = runTest(dispatcher) {
        // Room 写入失败被吞：乐观清零不回滚，tray 清理与已读回执照常走完。
        val ordinary = Chat(id = "c1", unreadCount = 2)
        var chats = listOf(ordinary)
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            withOwnerRoomWrite = { _, _ -> throw RuntimeException("room down") },
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertEquals(listOf("c1"), cancelled)
        assertEquals(listOf("c1"), receipts)
    }

    @Test
    fun markAllReadStaleSessionSkipsPersistenceButKeepsOptimisticZero() = runTest(dispatcher) {
        // 会话过期：持久化整段跳过，已乐观清零的 UI 不回滚。
        val ordinary = Chat(id = "c1", unreadCount = 2)
        var chats = listOf(ordinary)
        val cachedWrites = mutableListOf<Chat>()
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            isOwnerSessionCurrent = { false },
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun markAllReadCancellationAbortsRemainingChats() = runTest(dispatcher) {
        // 取消异常不被吞：首个会话抛 CancellationException 后整批中止，后续会话的 tray 清理也不跑。
        var chats = listOf(
            Chat(id = "c1", unreadCount = 2),
            Chat(id = "c2", unreadCount = 1),
        )
        val cancelled = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            withOwnerRoomWrite = { _, _ -> throw CancellationException("gone") },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
            cancelMessageNotification = { cancelled += it },
        )

        coordinator.markAllUnreadChatsRead()
        advanceUntilIdle()

        assertEquals(0, chats.first { it.id == "c1" }.unreadCount)
        assertEquals(0, chats.first { it.id == "c2" }.unreadCount)
        assertTrue(cancelled.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun markAllReadTwiceIsIdempotent() = runTest(dispatcher) {
        val ordinary = Chat(id = "c1", unreadCount = 2)
        var chats = listOf(ordinary)
        val cachedWrites = mutableListOf<Chat>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
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
        var chats = listOf(a)
        val cachedWrites = mutableListOf<Chat>()
        val receipts = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getChats = { chats },
            updateChats = { transform -> chats = transform(chats) },
            getSelectedChatIds = { emptySet() },
            cacheChats = { cachedWrites += it },
            enqueueReadReceipt = { cid, _, _ -> receipts += cid },
        )

        coordinator.batchMarkReadSelected()
        advanceUntilIdle()

        assertEquals(2, chats.single().unreadCount)
        assertTrue(cachedWrites.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    private fun kotlinx.coroutines.CoroutineScope.buildCoordinator(
        getChats: () -> List<Chat>,
        updateChats: ((List<Chat>) -> List<Chat>) -> Unit,
        getSelectedChatIds: () -> Set<String> = { emptySet() },
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
        return ChatListUnreadBatchCoordinator(
            scope = this,
            getChats = getChats,
            updateChats = updateChats,
            getSelectedChatIds = getSelectedChatIds,
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
            hasSession = { true },
        )
    }
}
