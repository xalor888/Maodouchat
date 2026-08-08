package com.maodouchat.call

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
}
