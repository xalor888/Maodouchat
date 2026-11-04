package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakePayloadPolicyTest {

    @Test
    fun `accepts routing-only wake metadata`() {
        assertTrue(
            PushWakePayloadPolicy.isSafe(
                mapOf("type" to "INCOMING_CALL", "callId" to "c1", "senderId" to "u1"),
            ),
        )
    }

    @Test
    fun `rejects empty payload`() {
        val decision = PushWakePayloadPolicy.evaluate(emptyMap())
        assertTrue(decision is PushWakeDecision.Reject)
        assertEquals("empty", (decision as PushWakeDecision.Reject).reason)
    }

    @Test
    fun `rejects sensitive chat keys`() {
        val decision = PushWakePayloadPolicy.evaluate(
            mapOf("type" to "NEW_MESSAGE", "preview" to "secret"),
        )
        assertTrue(decision is PushWakeDecision.Reject)
        assertTrue((decision as PushWakeDecision.Reject).reason.contains("preview"))
    }

    @Test
    fun `rejects unknown keys`() {
        val decision = PushWakePayloadPolicy.evaluate(
            mapOf("type" to "PING", "mystery" to "x"),
        )
        assertTrue(decision is PushWakeDecision.Reject)
        assertTrue((decision as PushWakeDecision.Reject).reason.contains("mystery"))
    }
}
