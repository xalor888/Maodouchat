package com.maodouchat.core.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRealtimeEventDispatcherTest {

    @Test
    fun testWakeEventsCoalesce() = runTest {
        val dispatcher = DefaultRealtimeEventDispatcher(wakeCoalesceMs = 100L)
        val collectedWakes = mutableListOf<RealtimeDomainEvent.Wake>()

        val job = launch {
            dispatcher.wakeEvents.collect {
                collectedWakes.add(it)
            }
        }

        // Rapidly dispatch 5 wake events within 50ms
        repeat(5) {
            dispatcher.dispatch(RealtimeDomainEvent.Wake())
            advanceTimeBy(10L)
        }

        // Before debounce window finishes: no wake emitted yet
        assertEquals(0, collectedWakes.size)

        // Advance past debounce window (100ms)
        advanceTimeBy(120L)

        // Only 1 wake event was emitted due to coalescing!
        assertEquals(1, collectedWakes.size)

        job.cancel()
    }

    @Test
    fun testTypedEventRouting() = runTest {
        val dispatcher = DefaultRealtimeEventDispatcher()
        val collectedTyping = mutableListOf<RealtimeDomainEvent.Typing>()
        val collectedPresence = mutableListOf<RealtimeDomainEvent.Presence>()

        val jobTyping = launch {
            dispatcher.typingEvents.collect { collectedTyping.add(it) }
        }
        val jobPresence = launch {
            dispatcher.presenceEvents.collect { collectedPresence.add(it) }
        }
        testScheduler.runCurrent()

        dispatcher.dispatch(RealtimeDomainEvent.Typing(userId = "u1", chatId = "c1", isTyping = true))
        dispatcher.dispatch(RealtimeDomainEvent.Presence(userId = "u2", isOnline = true))
        testScheduler.runCurrent()

        assertEquals(1, collectedTyping.size)
        assertEquals("u1", collectedTyping.first().userId)
        assertTrue(collectedTyping.first().isTyping)

        assertEquals(1, collectedPresence.size)
        assertEquals("u2", collectedPresence.first().userId)
        assertTrue(collectedPresence.first().isOnline)

        jobTyping.cancel()
        jobPresence.cancel()
    }

    @Test
    fun testConnectionStateTracking() {
        val dispatcher = DefaultRealtimeEventDispatcher()
        assertEquals(RealtimeConnectionState.DISCONNECTED, dispatcher.connectionState.value)

        dispatcher.updateConnectionState(RealtimeConnectionState.CONNECTING)
        assertEquals(RealtimeConnectionState.CONNECTING, dispatcher.connectionState.value)

        dispatcher.updateConnectionState(RealtimeConnectionState.CONNECTED)
        assertEquals(RealtimeConnectionState.CONNECTED, dispatcher.connectionState.value)
    }
}
