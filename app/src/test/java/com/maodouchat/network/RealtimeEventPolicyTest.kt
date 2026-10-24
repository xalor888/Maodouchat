package com.maodouchat.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealtimeEventPolicyTest {
    @Test
    fun `new subscriber does not receive an old business event`() = runTest {
        // 独立作用域：bus 的消费协程是常驻 for 循环，跑完需显式 cancel 避免 UncompletedCoroutinesError
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 4, scope = busScope)
            bus.post("old-delete")
            // 让消费协程把已入队事件发射到底层 flow（replay=0，尚无订阅者时直接丢弃）
            advanceUntilIdle()

            val next = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.first() }
            bus.post("new-message")
            advanceUntilIdle()

            assertEquals("new-message", next.await())
            assertTrue(bus.flow.replayCache.isEmpty())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `superseded websocket session cannot mutate current connection`() {
        val gate = WebSocketSessionGate()
        val first = gate.nextSession()
        val second = gate.nextSession()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))

        gate.invalidate()
        assertFalse(gate.isCurrent(second))
    }

    @Test
    fun `session gate exposes current generation`() {
        val gate = WebSocketSessionGate()
        val first = gate.nextSession()
        assertEquals(first, gate.current())
        val second = gate.nextSession()
        assertTrue(second > first)
        assertEquals(second, gate.current())
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
        assertEquals(second + 1, gate.current())
    }

    @Test
    fun `bus coerces zero capacity to one`() = runTest {
        // capacity=0 会被钳到 1：第二个 post 把第一个挤掉。
        // 订阅者必须先就位（UNDISPATCHED 立即订阅）：bus 的底层 flow replay=0，
        // 消费协程若在订阅前就把事件桥接过去，事件会直接丢失，await 永远挂起。
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 0, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.first() }
            bus.post("first")
            bus.post("second")
            advanceUntilIdle()
            assertEquals("second", got.await())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `bus drops oldest event when over capacity`() = runTest {
        // 先订阅再 post：消费协程尚未调度时队列积压（DROP_OLDEST 丢最旧），
        // 订阅者已就位后 advance，消费协程把 b、c 桥接给订阅者。
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 2, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.take(2).toList() }
            bus.post("a")
            bus.post("b")
            bus.post("c")
            advanceUntilIdle()
            assertEquals(listOf("b", "c"), got.await())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `typing event ignored when no active chat`() {
        assertFalse(shouldApplyTypingEvent("", "chat-a"))
    }

    @Test
    fun `offline event clears presence and takes event lastSeen`() {
        val result = resolveUserVisibility(
            currentIsOnline = true,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = false,
            eventLastSeen = 20L,
            onlineRevoked = false,
            statusRevoked = false
        )

        assertFalse(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(20L, result.lastSeen)
    }

    @Test
    fun `typing event only applies to active chat`() {
        assertTrue(shouldApplyTypingEvent("chat-a", "chat-a"))
        assertFalse(shouldApplyTypingEvent("chat-a", "chat-b"))
        assertFalse(shouldApplyTypingEvent("", ""))
        assertEquals(3_000L, REMOTE_TYPING_TIMEOUT_MS)
    }

    @Test
    fun `outgoing typing only signals state transitions`() {
        assertEquals(TypingSignalAction.START, resolveTypingSignalAction(isAnnounced = false, hasInput = true))
        assertEquals(TypingSignalAction.NONE, resolveTypingSignalAction(isAnnounced = true, hasInput = true))
        assertEquals(TypingSignalAction.STOP, resolveTypingSignalAction(isAnnounced = true, hasInput = false))
        assertEquals(TypingSignalAction.NONE, resolveTypingSignalAction(isAnnounced = false, hasInput = false))
    }

    @Test
    fun `regular presence event updates presence and preserves status`() {
        val result = resolveUserVisibility(
            currentIsOnline = false,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 20L,
            onlineRevoked = false,
            statusRevoked = false
        )

        assertTrue(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(20L, result.lastSeen)
    }

    @Test
    fun `online revocation clears presence but preserves status`() {
        val result = resolveUserVisibility(
            currentIsOnline = true,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 20L,
            onlineRevoked = true,
            statusRevoked = false
        )

        assertFalse(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(0L, result.lastSeen)
    }

    @Test
    fun `status revocation clears status but preserves presence`() {
        val result = resolveUserVisibility(
            currentIsOnline = true,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = false,
            eventLastSeen = 20L,
            onlineRevoked = false,
            statusRevoked = true
        )

        assertTrue(result.isOnline)
        assertEquals("", result.status)
        assertEquals(10L, result.lastSeen)
    }

    @Test
    fun `combined revocation clears presence and status`() {
        val result = resolveUserVisibility(
            currentIsOnline = true,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 20L,
            onlineRevoked = true,
            statusRevoked = true
        )

        assertFalse(result.isOnline)
        assertEquals("", result.status)
        assertEquals(0L, result.lastSeen)
    }

    /**
     * 客户端能收到的**全部** WS 事件类型，冻结成棘轮。
     *
     * **本轮修复**：此前这里的白名单是一份**虚构的名字表**（`Typing` / `UserStatusChanged` /
     * `CallOffer` …），与 `WebSocketEvent` 的真实子类（`UserTyping` / `UserOnline` /
     * `SignalingReceived` …）完全对不上，所以它一直在红——只是这个文件本身编译不过，
     * 红也没人看见。两处都会说谎：一份对不上的白名单，和一次从未执行过的断言。
     *
     * 现在改成：真实子类集合**精确相等** + **非空守卫** + 具名正文投递事件黑名单。
     * 非空守卫是关键——`sealedSubclasses` 若因任何原因返回空集合，原写法会静默通过。
     */
    @Test
    fun `client websocket event definition contains no human chat payload events`() {
        val eventClasses = WebSocketEvent::class.sealedSubclasses
        val actual = eventClasses.mapNotNull { it.simpleName }.toSet()

        val frozen = setOf(
            "AdminBroadcast",
            "Connected",
            "DisappearingMessagesUpdated",
            "Disconnected",
            "Error",
            "FriendRequestUpdated",
            "GroupInviteUpdated",
            "GroupPlayUpdated",
            "GroupRevisionChanged",
            "InboxAvailableV2",
            "PinnedMessagesUpdated",
            "PostDeleted",
            "ServerError",
            "SignalingReceived",
            "UserOnline",
            "UserTyping",
        )

        assertTrue(
            "WebSocketEvent 的密封子类枚举为空——枚举本身失效了，这个断言不能算通过",
            actual.isNotEmpty()
        )
        assertEquals(
            "客户端 WS 事件集合变了。新增一个类型意味着它会从网络进到 UI——" +
                "请先确认它不承载人类消息正文，再把这个基线改大；减少是好消息，请改小。",
            frozen.sorted(),
            actual.sorted()
        )

        val forbiddenEventNames = setOf(
            "NewMessage", "Message", "ChatMessage", "MessageReceived", "MessageData",
            "MessageContent", "MessagePayload", "SendMessage", "IncomingMessage",
        )
        assertEquals(
            "出现了「人肉正文投递」形状的 WS 事件类型",
            emptySet<String>(),
            actual.intersect(forbiddenEventNames)
        )

        // 明确记录哪些名字**看起来**可疑但已被核对过：
        //   *MessagesUpdated 是「置顶/阅后即焚设置变更」通知，载荷只有
        //   chatId/messageId/pinnedBy/pinnedAt（见 PinnedMessageDto），不含正文；
        //   AdminBroadcast 的 text 是运营公告，不是用户消息。
        assertTrue(
            "这几个已核对过的类型必须仍在集合里，否则上面的豁免注释就失效了",
            actual.containsAll(setOf("PinnedMessagesUpdated", "DisappearingMessagesUpdated", "AdminBroadcast"))
        )
    }

    @Test
    fun `fresh gate starts at zero generation`() {
        val gate = WebSocketSessionGate()
        assertEquals(0L, gate.current())
        assertTrue(gate.isCurrent(0L))
        assertFalse(gate.isCurrent(1L))
    }

    @Test
    fun `bus coerces negative capacity to one`() = runTest {
        // capacity 为负同样钳到 1：第二个 post 把第一个挤掉。
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = -1, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.first() }
            bus.post("first")
            bus.post("second")
            advanceUntilIdle()
            assertEquals("second", got.await())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `bus delivers events in order without overflow`() = runTest {
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 4, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.take(3).toList() }
            bus.post("a")
            bus.post("b")
            bus.post("c")
            advanceUntilIdle()
            assertEquals(listOf("a", "b", "c"), got.await())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `status revocation preserves current offline state`() {
        val result = resolveUserVisibility(
            currentIsOnline = false,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 99L,
            onlineRevoked = false,
            statusRevoked = true
        )

        assertFalse(result.isOnline)
        assertEquals("", result.status)
        assertEquals(10L, result.lastSeen)
    }

    @Test
    fun `double invalidate advances generation twice`() {
        val gate = WebSocketSessionGate()
        val first = gate.nextSession()
        gate.invalidate()
        gate.invalidate()

        assertEquals(first + 2, gate.current())
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(first + 1))
        assertTrue(gate.isCurrent(first + 2))
    }

    @Test
    fun `whitespace-only active chat ignores matching typing event`() {
        assertFalse(shouldApplyTypingEvent("   ", "   "))
        assertFalse(shouldApplyTypingEvent("chat-a", "   "))
    }

    @Test
    fun `online revocation on already-offline user still zeroes lastSeen`() {
        val result = resolveUserVisibility(
            currentIsOnline = false,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 20L,
            onlineRevoked = true,
            statusRevoked = false
        )

        assertFalse(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(0L, result.lastSeen)
    }

    @Test
    fun `online event with zero lastSeen passes zero through`() {
        val result = resolveUserVisibility(
            currentIsOnline = false,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = true,
            eventLastSeen = 0L,
            onlineRevoked = false,
            statusRevoked = false
        )

        assertTrue(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(0L, result.lastSeen)
    }

    @Test
    fun `gate session after invalidate keeps incrementing`() {
        val gate = WebSocketSessionGate()
        assertEquals(1L, gate.nextSession())
        gate.invalidate()
        assertEquals(2L, gate.current())
        assertEquals(3L, gate.nextSession())
        assertTrue(gate.isCurrent(3L))
        assertFalse(gate.isCurrent(2L))
        assertFalse(gate.isCurrent(1L))
    }

    @Test
    fun `bus capacity one keeps only the last of three posts`() = runTest {
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 1, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.first() }
            bus.post("a")
            bus.post("b")
            bus.post("c")
            advanceUntilIdle()
            assertEquals("c", got.await())
        } finally {
            busScope.cancel()
        }
    }

    @Test
    fun `offline event on already-offline user takes event lastSeen`() {
        val result = resolveUserVisibility(
            currentIsOnline = false,
            currentStatus = "Busy",
            currentLastSeen = 10L,
            eventIsOnline = false,
            eventLastSeen = 30L,
            onlineRevoked = false,
            statusRevoked = false
        )

        assertFalse(result.isOnline)
        assertEquals("Busy", result.status)
        assertEquals(30L, result.lastSeen)
    }

    @Test
    fun `typing event with trailing space does not match`() {
        assertFalse(shouldApplyTypingEvent("chat-a", "chat-a "))
        assertFalse(shouldApplyTypingEvent("chat-a ", "chat-a"))
    }

    @Test
    fun `gate rejects negative generation`() {
        val gate = WebSocketSessionGate()
        assertEquals(0L, gate.current())
        assertFalse(gate.isCurrent(-1L))
    }

    @Test
    fun `two sessions then invalidate leaves only post-invalidate generation current`() {
        val gate = WebSocketSessionGate()
        val first = gate.nextSession()
        val second = gate.nextSession()
        gate.invalidate()

        assertEquals(3L, gate.current())
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(3L))
    }

    @Test
    fun `bus capacity three drops only oldest of four posts`() = runTest {
        val busScope = CoroutineScope(coroutineContext + Job())
        try {
            val bus = NonReplayingEventBus<String>(capacity = 3, scope = busScope)
            val got = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.take(3).toList() }
            bus.post("a")
            bus.post("b")
            bus.post("c")
            bus.post("d")
            advanceUntilIdle()
            assertEquals(listOf("b", "c", "d"), got.await())
        } finally {
            busScope.cancel()
        }
    }
}
