package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { CallOrchestrator.groupCallRequests.take(2).toList() }
        }
        CallOrchestrator.requestGroupCall("chat-1", listOf("u1"), CallType.AUDIO)
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
}
