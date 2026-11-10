package com.maodouchat.domain.calls

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActionBusTest {
    @Test
    fun `foreground notification hangup reaches active collector`() = runTest {
        val gen = 7L
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-a", sessionGeneration = gen))
        val hangUp = received.await()
        assertEquals("call-a", hangUp.callId)
        assertTrue(hangUp.notifyPeer)
        assertEquals(gen, hangUp.sessionGeneration)
    }

    @Test
    fun `later hangup can carry a newer generation`() = runTest {
        val before = 7L
        val after = 8L
        assertTrue(after > before)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-b", notifyPeer = false, sessionGeneration = before))
        assertEquals(before, first.await().sessionGeneration)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-b", notifyPeer = false, sessionGeneration = after))
        val hangUp = second.await()
        assertEquals(after, hangUp.sessionGeneration)
        assertEquals(false, hangUp.notifyPeer)
    }

    @Test
    fun `hangups are delivered in emission order`() = runTest {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallActionBus.hangUpRequests.take(2).toList() }
        }
        assertTrue(CallActionBus.requestHangUp("call-1", sessionGeneration = 1L))
        assertTrue(CallActionBus.requestHangUp("call-2", sessionGeneration = 1L))
        val (first, second) = collected.await()
        assertEquals("call-1", first.callId)
        assertEquals("call-2", second.callId)
    }

    @Test
    fun `duplicate hangups for the same call id are both delivered`() = runTest {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallActionBus.hangUpRequests.take(2).toList() }
        }
        assertTrue(CallActionBus.requestHangUp("dup", sessionGeneration = 1L))
        assertTrue(CallActionBus.requestHangUp("dup", sessionGeneration = 1L))
        val events = collected.await()
        assertEquals(2, events.size)
        assertTrue(events.all { it.callId == "dup" })
    }

    @Test
    fun `blank call id passes through without validation`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("", sessionGeneration = 1L))
        assertEquals("", received.await().callId)
    }

    @Test
    fun `requestHangUp returns true and the event is collectable`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("call-ok", sessionGeneration = 1L))
        assertEquals("call-ok", received.await().callId)
    }

    @Test
    fun `hangup request carries the supplied generation verbatim`() {
        val req = HangUpRequest("call-m", sessionGeneration = 9L)
        assertEquals(9L, req.sessionGeneration)
        assertTrue(req.notifyPeer)
    }

    @Test
    fun `stale and fresh hangups carry distinguishable generations`() = runTest {
        // 失效前发出的 hangup 携带旧 generation——收集方据此丢弃换号前的陈旧请求。
        val genBefore = 7L
        val staleCollected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("stale-call", sessionGeneration = genBefore))
        val stale = staleCollected.await()
        assertEquals(genBefore, stale.sessionGeneration)

        val genAfter = 8L
        val freshCollected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("fresh-call", sessionGeneration = genAfter))
        val fresh = freshCollected.await()
        assertEquals(genAfter, fresh.sessionGeneration)
        assertTrue(stale.sessionGeneration < fresh.sessionGeneration)
    }

    @Test
    fun `hangup request is a value type`() {
        val a = HangUpRequest("call-v", sessionGeneration = 3L)
        val b = HangUpRequest("call-v", sessionGeneration = 3L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val renamed = a.copy(callId = "call-w")
        assertEquals("call-w", renamed.callId)
        assertEquals(a.notifyPeer, renamed.notifyPeer)
        assertEquals(a.sessionGeneration, renamed.sessionGeneration)
    }

    @Test
    fun `repeated requestHangUp always returns true and delivers in order`() = runTest {
        // 有订阅者时连续发射不丢：tryEmit 恒成功且按序到达。
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallActionBus.hangUpRequests.take(3).toList() }
        }
        repeat(3) { i ->
            assertTrue(CallActionBus.requestHangUp("rapid-$i", sessionGeneration = 1L))
        }
        val events = collected.await()
        assertEquals(listOf("rapid-0", "rapid-1", "rapid-2"), events.map { it.callId })
    }

    @Test
    fun `emit without subscriber is dropped despite tryEmit true`() = runTest {
        // SharedFlow 文档行为：无订阅者时 extraBufferCapacity 不生效，
        // tryEmit 照样返回 true，但值被丢弃（只有 replay 会留给迟到订阅者）。
        // 生产侧收集器（CallLifecycleController）在可发射时总是已订阅，
        // 这里把该语义钉死，防止后人误以为"buffer 能接住迟到订阅者"。
        assertTrue(CallActionBus.requestHangUp("unheard-call", sessionGeneration = 1L))
        val got = withTimeoutOrNull(500) { CallActionBus.hangUpRequests.first() }
        assertNull(got)
    }

    @Test
    fun `notifyPeer defaults to true through the bus`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallActionBus.hangUpRequests.first() }
        }
        assertTrue(CallActionBus.requestHangUp("peer-default", sessionGeneration = 1L))
        assertTrue(received.await().notifyPeer)
    }
}
