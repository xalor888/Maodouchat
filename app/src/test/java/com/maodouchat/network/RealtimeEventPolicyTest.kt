package com.maodouchat.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventPolicyTest {
    @Test
    fun `new subscriber does not receive an old business event`() = runTest {
        val bus = NonReplayingEventBus<String>(capacity = 4)
        assertTrue(bus.tryEmit("old-delete"))

        val next = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }
        assertTrue(bus.tryEmit("new-message"))

        assertEquals("new-message", next.await())
        assertTrue(bus.events.replayCache.isEmpty())
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
}
