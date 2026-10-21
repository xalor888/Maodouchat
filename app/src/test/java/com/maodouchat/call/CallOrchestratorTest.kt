package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOrchestratorTest {
    @Test
    fun invalidateSessionBumpsGenerationAndTagsRequests() = runTest {
        val before = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.invalidateSession()
        val after = CallOrchestrator.currentSessionGeneration()
        assertNotEquals(before, after)

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        CallOrchestrator.requestGroupCall("chat-1", listOf("u1"), CallType.AUDIO)
        val req = received.await()
        // Request is tagged with the generation at emit time; collectors drop stale gens.
        assertEquals(after, req.sessionGeneration)
        assertEquals(after, CallOrchestrator.currentSessionGeneration())
        assertTrue(after > before)
    }

    @Test
    fun directChatRequestCarriesCurrentGeneration() {
        CallOrchestrator.invalidateSession()
        val gen = CallOrchestrator.currentSessionGeneration()
        // Construction via public request path is covered by generation monotonicity;
        // data class default also uses currentSessionGeneration().
        val req = DirectChatRequest("user-x", "Name")
        assertEquals(gen, req.sessionGeneration)
    }

    @Test
    fun groupCallRequestEmitsPayloadWithCurrentGeneration() = runTest {
        val gen = CallOrchestrator.currentSessionGeneration()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        CallOrchestrator.requestGroupCall("chat-9", listOf("u1", "u2"), CallType.VIDEO)
        val req = received.await()
        assertEquals("chat-9", req.chatId)
        assertEquals(listOf("u1", "u2"), req.memberIds)
        assertEquals(CallType.VIDEO, req.callType)
        assertEquals(gen, req.sessionGeneration)
    }

    @Test
    fun directChatRequestEmitsWithCurrentGeneration() = runTest {
        val gen = CallOrchestrator.currentSessionGeneration()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.directChatRequests.first() }
        }
        CallOrchestrator.requestDirectChat("user-y", "Yara")
        val req = received.await()
        assertEquals("user-y", req.userId)
        assertEquals("Yara", req.userName)
        assertEquals(gen, req.sessionGeneration)
    }

    @Test
    fun sessionGenerationIsStrictlyMonotonic() {
        val g1 = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.invalidateSession()
        val g2 = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.invalidateSession()
        val g3 = CallOrchestrator.currentSessionGeneration()
        assertTrue(g2 > g1)
        assertTrue(g3 > g2)
    }

    @Test
    fun requestsAcrossInvalidateCarryDistinctGenerations() = runTest {
        val genBefore = CallOrchestrator.currentSessionGeneration()
        // buffer 只有 1：tryEmit 永远先入 buffer 再唤醒收集器，而唤醒是排队到
        // dispatcher 上的；两次发射之间必须 runCurrent 让收集器先消费第一条，
        // 否则第二条会因 buffer 满被静默丢弃。
        val subscribed = CompletableDeferred<Unit>()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CallOrchestrator.groupCallRequests
                    .onSubscription { subscribed.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        subscribed.await()
        CallOrchestrator.requestGroupCall("chat-1", listOf("u1"), CallType.AUDIO)
        runCurrent() // 让收集器消费第一条，腾出 buffer
        CallOrchestrator.invalidateSession()
        val genAfter = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.requestGroupCall("chat-2", listOf("u2"), CallType.AUDIO)
        val (first, second) = collected.await()
        assertEquals(genBefore, first.sessionGeneration)
        assertEquals(genAfter, second.sessionGeneration)
        assertTrue(first.sessionGeneration < second.sessionGeneration)
    }

    @Test
    fun groupCallRequestWithEmptyMembersStillEmits() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        CallOrchestrator.requestGroupCall("chat-empty", emptyList(), CallType.AUDIO)
        val req = received.await()
        assertEquals("chat-empty", req.chatId)
        assertTrue(req.memberIds.isEmpty())
    }

    @Test
    fun rapidEmitsWithoutCollectorFirstWinsWhenBufferFull() = runTest {
        // buffer=1：无订阅者时第二次 tryEmit 静默丢弃，第一条保留。
        CallOrchestrator.requestGroupCall("chat-first", listOf("u1"), CallType.AUDIO)
        CallOrchestrator.requestGroupCall("chat-second", listOf("u2"), CallType.VIDEO)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        assertEquals("chat-first", received.await().chatId)
    }

    @Test
    fun groupCallRequestDefaultGenerationIsCurrent() {
        CallOrchestrator.invalidateSession()
        val req = GroupCallRequest("chat-d", listOf("u1"), CallType.AUDIO)
        assertEquals(CallOrchestrator.currentSessionGeneration(), req.sessionGeneration)
    }

    @Test
    fun memberIdsDuplicatesAndOrderPassThroughVerbatim() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        CallOrchestrator.requestGroupCall("chat-dup", listOf("u2", "u1", "u2"), CallType.AUDIO)
        assertEquals(listOf("u2", "u1", "u2"), received.await().memberIds)
    }

    @Test
    fun directChatEmptyUserNameStillEmits() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.directChatRequests.first() }
        }
        CallOrchestrator.requestDirectChat("user-z", "")
        val req = received.await()
        assertEquals("user-z", req.userId)
        assertEquals("", req.userName)
    }

    @Test
    fun directChatRequestsAcrossInvalidateCarryDistinctGenerations() = runTest {
        val genBefore = CallOrchestrator.currentSessionGeneration()
        // 与群通话路径同理：buffer 只有 1，两次发射之间必须 runCurrent 让收集器先消费。
        val subscribed = CompletableDeferred<Unit>()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CallOrchestrator.directChatRequests
                    .onSubscription { subscribed.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        subscribed.await()
        CallOrchestrator.requestDirectChat("user-a", "A")
        runCurrent()
        CallOrchestrator.invalidateSession()
        val genAfter = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.requestDirectChat("user-b", "B")
        val (first, second) = collected.await()
        assertEquals(genBefore, first.sessionGeneration)
        assertEquals(genAfter, second.sessionGeneration)
        assertTrue(first.sessionGeneration < second.sessionGeneration)
    }
}
