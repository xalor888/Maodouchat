package com.maodouchat.call

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActionBusTest {
    @Test
    fun `foreground notification hangup reaches active collector`() = runTest {
        val gen = MaodouchatApp.currentSessionGeneration()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-a"))
        val hangUp = received.await()
        assertEquals("call-a", hangUp.callId)
        assertTrue(hangUp.notifyPeer)
        assertEquals(gen, hangUp.sessionGeneration)
    }

    @Test
    fun `hangup stamped after invalidate is newer generation`() = runTest {
        val before = MaodouchatApp.currentSessionGeneration()
        MaodouchatApp.invalidateSessionGeneration()
        val after = MaodouchatApp.currentSessionGeneration()
        assertTrue(after > before)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-b", notifyPeer = false))
        val hangUp = received.await()
        assertEquals(after, hangUp.sessionGeneration)
        assertEquals(false, hangUp.notifyPeer)
    }

    @Test
    fun `hangups are delivered in emission order`() = runTest {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallActionBus.hangUpRequests.take(2).toList() }
        }
        assertTrue(CallActionBus.requestHangUp("call-1"))
        assertTrue(CallActionBus.requestHangUp("call-2"))
        val (first, second) = collected.await()
        assertEquals("call-1", first.callId)
        assertEquals("call-2", second.callId)
    }

    @Test
    fun `duplicate hangups for the same call id are both delivered`() = runTest {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallActionBus.hangUpRequests.take(2).toList() }
        }
        assertTrue(CallActionBus.requestHangUp("dup"))
        assertTrue(CallActionBus.requestHangUp("dup"))
        val events = collected.await()
        assertEquals(2, events.size)
        assertTrue(events.all { it.callId == "dup" })
    }

    @Test
    fun `blank call id passes through without validation`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp(""))
        assertEquals("", received.await().callId)
    }

    @Test
    fun `requestHangUp returns true and the event is collectable`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-ok"))
        assertEquals("call-ok", received.await().callId)
    }

    @Test
    fun `hangup request model defaults to current app generation`() {
        val req = HangUpRequest("call-m")
        assertEquals(MaodouchatApp.currentSessionGeneration(), req.sessionGeneration)
        assertTrue(req.notifyPeer)
    }
}
