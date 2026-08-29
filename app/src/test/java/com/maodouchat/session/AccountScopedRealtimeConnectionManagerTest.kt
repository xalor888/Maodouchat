package com.maodouchat.session

import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountScopedRealtimeConnectionManagerTest {
    private fun kotlinx.coroutines.test.TestScope.managerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun rejectsStaleOwnerAndToken() = runTest {
        val sessions = FakeSessionContextProvider()
        val transport = FakeRealtimeTransport()
        val manager = AccountScopedRealtimeConnectionManager(sessions, transport, managerScope())
        val alice = sessions.activate("alice", "alice-token")
        advanceUntilIdle()

        assertFalse(manager.start("wss://example.test/ws", "wrong-token", alice))

        sessions.activate("bob", "bob-token")
        advanceUntilIdle()
        assertFalse(manager.start("wss://example.test/ws", "alice-token", alice))
        assertEquals(0, transport.connectCount)
    }

    @Test
    fun accountChangeDisconnectsAndOldOwnerCannotStopReplacement() = runTest {
        val sessions = FakeSessionContextProvider()
        val transport = FakeRealtimeTransport()
        val manager = AccountScopedRealtimeConnectionManager(sessions, transport, managerScope())
        val alice = sessions.activate("alice", "alice-token")
        advanceUntilIdle()
        assertTrue(manager.start("wss://example.test/ws", "alice-token", alice))

        val bob = sessions.activate("bob", "bob-token")
        advanceUntilIdle()
        assertEquals(1, transport.disconnectCount)
        assertTrue(manager.start("wss://example.test/ws", "bob-token", bob))

        assertFalse(manager.stop(alice))
        assertEquals(1, transport.disconnectCount)
        assertTrue(manager.isConnected(bob))
    }

    @Test
    fun filtersLateEventsFromSupersededConnectionGeneration() = runTest {
        val sessions = FakeSessionContextProvider()
        val transport = FakeRealtimeTransport()
        val manager = AccountScopedRealtimeConnectionManager(sessions, transport, managerScope())
        val alice = sessions.activate("alice", "alice-token")
        advanceUntilIdle()
        assertTrue(manager.start("wss://example.test/ws", "alice-token", alice))
        val aliceConnection = transport.lastConnectionGeneration

        val bob = sessions.activate("bob", "bob-token")
        advanceUntilIdle()
        assertTrue(manager.start("wss://example.test/ws", "bob-token", bob))
        val bobConnection = transport.lastConnectionGeneration

        val received = async { manager.events.first() }
        advanceUntilIdle()
        transport.emit(aliceConnection, WebSocketEvent.InboxAvailableV2)
        advanceUntilIdle()
        assertFalse(received.isCompleted)

        transport.emit(bobConnection, WebSocketEvent.Connected(true))
        assertEquals(WebSocketEvent.Connected(true), received.await())
    }

    @Test
    fun invalidationRejectsSendsAndStopsOwnedTransport() = runTest {
        val sessions = FakeSessionContextProvider()
        val transport = FakeRealtimeTransport()
        val manager = AccountScopedRealtimeConnectionManager(sessions, transport, managerScope())
        val alice = sessions.activate("alice", "alice-token")
        advanceUntilIdle()
        assertTrue(manager.start("wss://example.test/ws", "alice-token", alice))
        assertTrue(manager.sendRaw(alice, "payload"))

        sessions.invalidate("alice")
        advanceUntilIdle()

        assertFalse(manager.sendRaw(alice, "stale"))
        assertFalse(manager.isConnected(alice))
        assertEquals(1, transport.disconnectCount)
        assertNull(withTimeoutOrNull(1) { manager.events.first() })
    }

    private class FakeSessionContextProvider : SessionContextProvider {
        private val state = MutableSessionContextProvider()
        private val tokens = mutableMapOf<SessionContext, String>()

        override val contexts = state.contexts

        fun activate(ownerUserId: String, token: String): SessionContext =
            state.activate(ownerUserId).also { tokens[it] = token }

        fun invalidate(ownerUserId: String) {
            state.invalidate(ownerUserId)
        }

        override fun isAccessTokenCurrent(context: SessionContext, accessToken: String): Boolean =
            isCurrent(context) && tokens[context] == accessToken
    }

    private class FakeRealtimeTransport : RealtimeTransport {
        private val eventFlow = MutableSharedFlow<RealtimeTransportEvent>(extraBufferCapacity = 8)
        override val events: SharedFlow<RealtimeTransportEvent> = eventFlow

        var connectCount = 0
        var disconnectCount = 0
        var lastConnectionGeneration = 0L
        private var connected = false

        override fun open(serverUrl: String, accessToken: String, reconnect: Boolean): Long {
            connectCount += 1
            lastConnectionGeneration += 1L
            connected = true
            return lastConnectionGeneration
        }

        override fun disconnect() {
            disconnectCount += 1
            connected = false
        }

        override fun isConnected(): Boolean = connected
        override fun sendRaw(text: String): Boolean = connected
        override fun sendTyping(chatId: String, isTyping: Boolean): Boolean = connected
        override fun sendPresence(foreground: Boolean): Boolean = connected

        fun emit(connectionGeneration: Long, event: WebSocketEvent) {
            eventFlow.tryEmit(RealtimeTransportEvent(connectionGeneration, event))
        }
    }
}
