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

    @Test
    fun `pre-invalidate hangup carries distinguishable stale generation`() = runTest {
        // 失效前发出的 hangup 携带旧 generation——收集方据此丢弃换号前的陈旧请求。
        val genBefore = MaodouchatApp.currentSessionGeneration()
        val staleCollected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("stale-call"))
        val stale = staleCollected.await()
        assertEquals(genBefore, stale.sessionGeneration)

        MaodouchatApp.invalidateSessionGeneration()
        val genAfter = MaodouchatApp.currentSessionGeneration()
        val freshCollected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("fresh-call"))
        val fresh = freshCollected.await()
        assertEquals(genAfter, fresh.sessionGeneration)
        assertTrue(stale.sessionGeneration < fresh.sessionGeneration)
    }

    @Test
    fun `hangup request is a value type`() {
        val a = HangUpRequest("call-v")
        val b = HangUpRequest("call-v")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val renamed = a.copy(callId = "call-w")
        assertEquals("call-w", renamed.callId)
        assertEquals(a.notifyPeer, renamed.notifyPeer)
        assertEquals(a.sessionGeneration, renamed.sessionGeneration)
    }

    @Test
    fun `repeated requestHangUp always returns true within buffer`() = runTest {
        // 16 槽 buffer：连续发射不丢，tryEmit 恒成功。
        repeat(3) { i ->
            assertTrue(CallActionBus.requestHangUp("rapid-$i"))
        }
        val events = withTimeout(2_000) { CallActionBus.hangUpRequests.take(3).toList() }
        assertEquals(listOf("rapid-0", "rapid-1", "rapid-2"), events.map { it.callId })
    }

    @Test
    fun `late subscriber receives buffered emit`() = runTest {
        // 无订阅者时发射被缓冲：迟到的订阅者仍能收到（稍后启动的 VM 接住前台服务的挂断）。
        assertTrue(CallActionBus.requestHangUp("late-call"))
        val received = withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        assertEquals("late-call", received.callId)
    }

    @Test
    fun `notifyPeer defaults to true through the bus`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("peer-default"))
        assertTrue(received.await().notifyPeer)
    }
}
