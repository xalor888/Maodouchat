package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * 从 app 侧随迁（原 `ChatListMissedCallAndArchiveCoordinatorTest` 中的未接来电 3 例）：
 * `ChatListUiState` 经端口替换为本地持有，`BackgroundSessionGate` 与 `Log` 经端口注入；
 * 归档建议 3 例留在 app 侧测试文件。另新增清空调托盘、门禁关闭、空账号 3 例。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListMissedCallCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    private fun buildCoordinator(
        scope: CoroutineScope,
        getMissedCalls: () -> List<MissedCall>,
        getChats: () -> List<Chat>,
        updateMissedCalls: ((List<MissedCall>) -> List<MissedCall>) -> Unit,
        ownerUserId: () -> String = { "me" },
        markAllRead: suspend () -> Unit = {},
        clearAll: suspend () -> Unit = {},
        deleteCall: suspend (callId: String) -> Unit = {},
        cancelMissedCallNotification: (callId: String) -> Unit = {},
        removeCenterItem: suspend (id: String) -> Unit = {},
        sessionActive: (String) -> Boolean = { true },
        logWarning: (String, Throwable?) -> Unit = { _, _ -> },
    ): ChatListMissedCallCoordinator = ChatListMissedCallCoordinator(
        scope = scope,
        getMissedCalls = getMissedCalls,
        getChats = getChats,
        updateMissedCalls = updateMissedCalls,
        ownerUserId = ownerUserId,
        markAllRead = markAllRead,
        clearAll = clearAll,
        deleteCall = deleteCall,
        cancelMissedCallNotification = cancelMissedCallNotification,
        removeCenterItem = removeCenterItem,
        sessionActive = sessionActive,
        logWarning = logWarning,
    )

    @Test
    fun markMissedCallsReadClearsTrayAndCenter() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        var missedCalls = listOf(call)
        var marked = false
        val cancelled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val coordinator = buildCoordinator(
            scope = this,
            getMissedCalls = { missedCalls },
            getChats = { emptyList() },
            updateMissedCalls = { missedCalls = it(missedCalls) },
            markAllRead = { marked = true },
            cancelMissedCallNotification = { cancelled += it },
            removeCenterItem = { removed += it },
        )

        coordinator.markMissedCallsRead()
        advanceUntilIdle()

        assertTrue(marked)
        assertEquals(listOf("m1"), cancelled)
        assertEquals(listOf("missed_m1"), removed)
    }

    @Test
    fun clearMissedCallsClearsTrayAndCenter() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        var missedCalls = listOf(call)
        var cleared = false
        val cancelled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val coordinator = buildCoordinator(
            scope = this,
            getMissedCalls = { missedCalls },
            getChats = { emptyList() },
            updateMissedCalls = { missedCalls = it(missedCalls) },
            clearAll = { cleared = true },
            cancelMissedCallNotification = { cancelled += it },
            removeCenterItem = { removed += it },
        )

        coordinator.clearMissedCalls()
        advanceUntilIdle()

        assertTrue(cleared)
        assertEquals(listOf("m1"), cancelled)
        assertEquals(listOf("missed_m1"), removed)
    }

    @Test
    fun markMissedCallsReadSkipsWhenSessionInactive() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        var missedCalls = listOf(call)
        var marked = false
        val coordinator = buildCoordinator(
            scope = this,
            getMissedCalls = { missedCalls },
            getChats = { emptyList() },
            updateMissedCalls = { missedCalls = it(missedCalls) },
            markAllRead = { marked = true },
            sessionActive = { false },
        )

        coordinator.markMissedCallsRead()
        advanceUntilIdle()

        assertTrue(!marked)
    }

    @Test
    fun markMissedCallsReadSkipsWhenOwnerBlank() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        var missedCalls = listOf(call)
        var marked = false
        val coordinator = buildCoordinator(
            scope = this,
            getMissedCalls = { missedCalls },
            getChats = { emptyList() },
            updateMissedCalls = { missedCalls = it(missedCalls) },
            ownerUserId = { "" },
            markAllRead = { marked = true },
        )

        coordinator.markMissedCallsRead()
        advanceUntilIdle()

        assertTrue(!marked)
    }

    @Test
    fun removeMissedCallLocallyDropsUiAndDeletes() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        var missedCalls = listOf(call)
        var deleted: String? = null
        val cancelled = mutableListOf<String>()
        val coordinator = buildCoordinator(
            scope = this,
            getMissedCalls = { missedCalls },
            getChats = { emptyList() },
            updateMissedCalls = { missedCalls = it(missedCalls) },
            deleteCall = { deleted = it },
            cancelMissedCallNotification = { cancelled += it },
        )

        coordinator.removeMissedCallLocally("m1")
        advanceUntilIdle()

        assertTrue(missedCalls.isEmpty())
        assertEquals("m1", deleted)
        assertEquals(listOf("m1"), cancelled)
    }

    @Test
    fun findDirectChatIdForUserResolvesPeer() {
        val peer = User(id = "u2", name = "Peer")
        val chat = Chat(id = "c1", isGroup = false, participants = listOf(peer))
        val chats = listOf(chat)
        val coordinator = buildCoordinator(
            scope = CoroutineScope(dispatcher),
            getMissedCalls = { emptyList() },
            getChats = { chats },
            updateMissedCalls = {},
        )

        assertEquals("c1", coordinator.findDirectChatIdForUser("u2"))
        assertNull(coordinator.findDirectChatIdForUser("missing"))
    }
}
