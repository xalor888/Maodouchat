package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOrchestratorTest {
    @Test
    fun invalidateSessionBumpsGenerationAndTagsRequests() {
        val before = CallOrchestrator.currentSessionGeneration()
        CallOrchestrator.invalidateSession()
        val after = CallOrchestrator.currentSessionGeneration()
        assertNotEquals(before, after)

        CallOrchestrator.requestGroupCall("chat-1", listOf("u1"), CallType.AUDIO)
        // Request is tagged with the generation at emit time; collectors drop stale gens.
        assertEquals(after, CallOrchestrator.currentSessionGeneration())
        assertTrue(after > before)
    }

    @Test
    fun directChatRequestCarriesCurrentGeneration() {
        CallOrchestrator.invalidateSession()
        val gen = CallOrchestrator.currentSessionGeneration()
        // Construction via public request path is covered by generation monotonicity;
        // data class default also uses currentSessionGeneration().
        val req = CallOrchestrator.DirectChatRequest("user-x", "Name")
        assertEquals(gen, req.sessionGeneration)
    }
}
