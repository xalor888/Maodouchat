package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.Trigger
import com.maodouchat.core.model.User
import com.maodouchat.core.realtime.ChatMessageSentEvent
import com.maodouchat.core.realtime.ChatReadEvent
import com.maodouchat.core.realtime.DefaultRealtimeEventDispatcher
import com.maodouchat.core.realtime.RealtimeConnectionState
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListRealtimeCoordinatorTest {

    // Unconfined：backgroundScope 里 launch 的收集器立即开始收集；
    // StandardTestDispatcher 下 advanceUntilIdle 泵不到 backgroundScope，
    // 全部正向测试收不到事件（见 CallHangUpSenderTest 的同款注释）。
    private val dispatcher = UnconfinedTestDispatcher()

    private data class Preview(
        val chatId: String,
        val text: String,
        val type: MessageType,
        val timestamp: Long,
        val ownerUserId: String,
        val sessionGeneration: Long,
    )

    private data class VisibilityCall(
        val userId: String,
        val isOnline: Boolean,
        val onlineRevoked: Boolean,
        val statusRevoked: Boolean,
        val updatedAt: Long,
    )

    /** 替代 app 侧 ChatListUiState、通知中心与 Room 的最小状态。 */
    private class State {
        var chats: List<Chat> = emptyList()
        var banner: String? = null
        var notifications: List<Pair<NotificationCenterItem, String?>> = emptyList()
        var warnings: List<String> = emptyList()
        var cached: MutableMap<String, Chat> = mutableMapOf()
        var persisted: List<Chat> = emptyList()
        var loadRequests: List<Trigger> = emptyList()
        var previews: List<Preview> = emptyList()
        var refreshFromLocal: List<Triple<String, String, Long>> = emptyList()
        var clearedMarkedUnread: List<Chat> = emptyList()
        var backlogSyncCalls: Int = 0
        var visibilityCalls: List<VisibilityCall> = emptyList()
        var session: OwnerSessionSnapshot = OwnerSessionSnapshot("owner-1", 7L)
        var sessionCurrent: Boolean = true
        var sessionMayContinue: Boolean = true
        var now: Long = 1_000_000L
    }

    private fun CoroutineScope.buildController(
        state: State,
        eventDispatcher: DefaultRealtimeEventDispatcher = DefaultRealtimeEventDispatcher(),
        readEvents: MutableSharedFlow<ChatReadEvent> = MutableSharedFlow(),
        sentEvents: MutableSharedFlow<ChatMessageSentEvent> = MutableSharedFlow(),
    ) = ChatListRealtimeCoordinator(
        scope = this,
        getChats = { state.chats },
        updateChats = { transform -> state.chats = transform(state.chats) },
        setRealtimeBanner = { state.banner = it },
        realtimeEventDispatcher = eventDispatcher,
        addNotification = { item, userId -> state.notifications += item to userId },
        chatReadEvents = readEvents,
        chatMessageSentEvents = sentEvents,
        ownerUserId = { state.session.ownerUserId },
        ownerSession = { state.session },
        isOwnerSessionCurrent = { state.sessionCurrent },
        withOwnerRoomWrite = { _, block -> block(); true },
        getCachedChat = { state.cached[it] },
        cacheChats = { state.persisted += it },
        applyRealtimeVisibility = { userId, isOnline, onlineRevoked, statusRevoked, updatedAt ->
            state.visibilityCalls += VisibilityCall(userId, isOnline, onlineRevoked, statusRevoked, updatedAt)
        },
        wsConnectionFailedText = "WS down",
        adminBroadcastDefaultTitle = "Admin",
        adminBroadcastSenderLabel = "System",
        sessionActive = { state.sessionMayContinue },
        logWarning = { message, _ -> state.warnings += message },
        nowMillis = { state.now },
        onRequestLoadChats = { state.loadRequests += it },
        onApplyChatListPreview = { chatId, previewText, messageType, timestamp, ownerUserId, sessionGeneration ->
            state.previews += Preview(chatId, previewText, messageType, timestamp, ownerUserId, sessionGeneration)
        },
        onRefreshPreviewFromLocal = { chatId, ownerUserId, sessionGeneration ->
            state.refreshFromLocal += Triple(chatId, ownerUserId, sessionGeneration)
        },
        onClearMarkedUnreadAfterOpen = { state.clearedMarkedUnread += it },
        onRequestBacklogSync = { state.backlogSyncCalls++ },
    )

    private fun chat(
        id: String,
        unreadCount: Int = 0,
        markedUnread: Boolean = false,
        participants: List<User> = emptyList(),
    ) = Chat(id = id, unreadCount = unreadCount, markedUnread = markedUnread, participants = participants)

    @Test
    fun `read event zeroes unread, persists cached copy and clears marked unread`() = runTest(dispatcher) {
        val original = chat("c1", unreadCount = 3, markedUnread = true)
        val state = State().apply {
            chats = listOf(original)
            cached["c1"] = chat("c1", unreadCount = 2, markedUnread = true)
        }
        val readEvents = MutableSharedFlow<ChatReadEvent>()
        backgroundScope.buildController(state, readEvents = readEvents).start()
        advanceUntilIdle()
        readEvents.emit(ChatReadEvent(chatId = "c1", sessionGeneration = 7L))
        advanceUntilIdle()
        assertEquals(0, state.chats.single().unreadCount)
        assertFalse(state.chats.single().markedUnread)
        assertEquals(1, state.persisted.size)
        assertEquals("c1", state.persisted.single().id)
        assertEquals(0, state.persisted.single().unreadCount)
        assertFalse(state.persisted.single().markedUnread)
        assertEquals(listOf(original), state.clearedMarkedUnread)
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `read event with stale generation or blank chat id is ignored`() = runTest(dispatcher) {
        val state = State().apply { chats = listOf(chat("c1", unreadCount = 3)) }
        val readEvents = MutableSharedFlow<ChatReadEvent>()
        backgroundScope.buildController(state, readEvents = readEvents).start()
        advanceUntilIdle()
        readEvents.emit(ChatReadEvent(chatId = "c1", sessionGeneration = 8L))
        readEvents.emit(ChatReadEvent(chatId = "  ", sessionGeneration = 7L))
        advanceUntilIdle()
        assertEquals(3, state.chats.single().unreadCount)
        assertTrue(state.persisted.isEmpty())
        assertTrue(state.clearedMarkedUnread.isEmpty())
    }

    @Test
    fun `read event is ignored when session is not current`() = runTest(dispatcher) {
        val state = State().apply {
            chats = listOf(chat("c1", unreadCount = 3))
            sessionCurrent = false
        }
        val readEvents = MutableSharedFlow<ChatReadEvent>()
        backgroundScope.buildController(state, readEvents = readEvents).start()
        advanceUntilIdle()
        readEvents.emit(ChatReadEvent(chatId = "c1", sessionGeneration = 7L))
        advanceUntilIdle()
        assertEquals(3, state.chats.single().unreadCount)
    }

    @Test
    fun `message sent applies preview with injected time`() = runTest(dispatcher) {
        val state = State()
        val sentEvents = MutableSharedFlow<ChatMessageSentEvent>()
        backgroundScope.buildController(state, sentEvents = sentEvents).start()
        advanceUntilIdle()
        sentEvents.emit(ChatMessageSentEvent(chatId = "c1", previewText = "hi", messageTypeWire = "TEXT", sessionGeneration = 7L))
        advanceUntilIdle()
        assertEquals(
            listOf(Preview("c1", "hi", MessageType.TEXT, 1_000_000L, "owner-1", 7L)),
            state.previews,
        )
        assertTrue(state.refreshFromLocal.isEmpty())
    }

    @Test
    fun `message sent with forceFromLocal refreshes preview from local`() = runTest(dispatcher) {
        val state = State()
        val sentEvents = MutableSharedFlow<ChatMessageSentEvent>()
        backgroundScope.buildController(state, sentEvents = sentEvents).start()
        advanceUntilIdle()
        sentEvents.emit(ChatMessageSentEvent(chatId = "c1", forceFromLocal = true, sessionGeneration = 7L))
        advanceUntilIdle()
        assertEquals(listOf(Triple("c1", "owner-1", 7L)), state.refreshFromLocal)
        assertTrue(state.previews.isEmpty())
    }

    @Test
    fun `message sent with stale generation is ignored`() = runTest(dispatcher) {
        val state = State()
        val sentEvents = MutableSharedFlow<ChatMessageSentEvent>()
        backgroundScope.buildController(state, sentEvents = sentEvents).start()
        advanceUntilIdle()
        sentEvents.emit(ChatMessageSentEvent(chatId = "c1", previewText = "hi", sessionGeneration = 99L))
        advanceUntilIdle()
        assertTrue(state.previews.isEmpty())
    }

    @Test
    fun `admin notice stores notification and shows banner with default title`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(RealtimeDomainEvent.AdminNotice(title = "", text = "hello", timestamp = 5L))
        advanceUntilIdle()
        assertEquals(1, state.notifications.size)
        assertEquals("Admin", state.notifications.single().first.title)
        assertEquals("owner-1", state.notifications.single().second)
        assertEquals("Admin: hello", state.banner)
    }

    @Test
    fun `admin notice with blank body is ignored`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(RealtimeDomainEvent.AdminNotice(title = "T", text = "   ", timestamp = 5L))
        advanceUntilIdle()
        assertTrue(state.notifications.isEmpty())
        assertNull(state.banner)
    }

    @Test
    fun `admin notice still stores notification but hides banner when session inactive`() = runTest(dispatcher) {
        val state = State().apply { sessionMayContinue = false }
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(RealtimeDomainEvent.AdminNotice(title = "T", text = "hello", timestamp = 5L))
        advanceUntilIdle()
        assertEquals(1, state.notifications.size)
        assertNull(state.banner)
    }

    @Test
    fun `group revision requests load chats`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.GroupRevision(chatId = "g1", memberRevision = 3L, reason = "join"),
        )
        advanceUntilIdle()
        assertEquals(listOf(Trigger.GROUP_REVISION), state.loadRequests)
    }

    @Test
    fun `connected clears banner, requests load and backlog sync`() = runTest(dispatcher) {
        val state = State().apply { banner = "WS down" }
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.updateConnectionState(RealtimeConnectionState.CONNECTED)
        advanceUntilIdle()
        assertNull(state.banner)
        assertEquals(listOf(Trigger.RECONNECT), state.loadRequests)
        assertEquals(1, state.backlogSyncCalls)
    }

    @Test
    fun `disconnect shows banner only after delay`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED),
        )
        runCurrent()
        assertNull(state.banner)
        advanceTimeBy(2_499L)
        runCurrent()
        assertNull(state.banner)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("WS down", state.banner)
    }

    @Test
    fun `reconnect before delay cancels pending banner`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED),
        )
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        eventDispatcher.updateConnectionState(RealtimeConnectionState.CONNECTED)
        advanceUntilIdle()
        advanceTimeBy(5_000L)
        runCurrent()
        assertNull(state.banner)
    }

    @Test
    fun `clearRealtimeBanner cancels pending banner job`() = runTest(dispatcher) {
        val state = State()
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        val controller = backgroundScope.buildController(state, eventDispatcher = eventDispatcher)
        controller.start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED),
        )
        runCurrent()
        controller.clearRealtimeBanner()
        advanceTimeBy(5_000L)
        runCurrent()
        assertNull(state.banner)
    }

    @Test
    fun `presence applies projection and writes visibility when revoked`() = runTest(dispatcher) {
        val state = State().apply {
            chats = listOf(chat("c1", participants = listOf(User(id = "u1", name = "U1", isOnline = false))))
        }
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.Presence(userId = "u1", isOnline = true, lastSeen = 123L, onlineRevoked = true),
        )
        advanceUntilIdle()
        // 撤销时 resolveUserVisibility 强制离线/lastSeen 归零（事件 isOnline 只进 Room 回写）。
        assertFalse(state.chats.single().participants.single().isOnline)
        assertEquals(0L, state.chats.single().participants.single().lastSeen)
        assertEquals(
            listOf(VisibilityCall("u1", true, true, false, 1_000_000L)),
            state.visibilityCalls,
        )
    }

    @Test
    fun `presence is ignored when session inactive`() = runTest(dispatcher) {
        val state = State().apply {
            chats = listOf(chat("c1", participants = listOf(User(id = "u1", name = "U1", isOnline = false))))
            sessionMayContinue = false
        }
        val eventDispatcher = DefaultRealtimeEventDispatcher()
        backgroundScope.buildController(state, eventDispatcher = eventDispatcher).start()
        advanceUntilIdle()
        eventDispatcher.dispatch(
            RealtimeDomainEvent.Presence(userId = "u1", isOnline = true, lastSeen = 123L),
        )
        advanceUntilIdle()
        assertFalse(state.chats.single().participants.single().isOnline)
        assertTrue(state.visibilityCalls.isEmpty())
    }
}
