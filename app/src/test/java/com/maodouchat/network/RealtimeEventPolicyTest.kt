package com.maodouchat.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
}
