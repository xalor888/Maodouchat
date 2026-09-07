package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PushWakePayloadPolicyTest {

    @Test
    fun acceptsCallWakeMetadata() {
        assertTrue(
            PushWakePayloadPolicy.isSafe(
                mapOf(
                    "type" to "INCOMING_CALL",
                    "senderId" to "u1",
                    "callType" to "AUDIO",
                    "recipientId" to "u2",
                    "callId" to "c1",
                )
            )
        )
    }

    @Test
    fun rejectsChatBodyPreview() {
        val decision = PushWakePayloadPolicy.evaluate(
            mapOf(
                "type" to "NEW_MESSAGE",
                "chatId" to "g1",
                "preview" to "secret hello",
            )
        )
        assertIs<PushWakePayloadPolicy.Decision.Reject>(decision)
        assertTrue(decision.reason.contains("preview"))
    }

    @Test
    fun rejectsPlaintextBodyKeys() {
        assertFalse(
            PushWakePayloadPolicy.isSafe(
                mapOf("type" to "NEW_MESSAGE", "body" to "hi")
            )
        )
        assertFalse(
            PushWakePayloadPolicy.isSafe(
                mapOf("type" to "NEW_MESSAGE", "plaintext" to "hi")
            )
        )
    }

    @Test
    fun rejectsUnknownKeys() {
        val decision = PushWakePayloadPolicy.evaluate(
            mapOf("type" to "GROUP_INVITE", "extraLeak" to "x")
        )
        assertIs<PushWakePayloadPolicy.Decision.Reject>(decision)
        assertEquals("unknown_keys=extraleak", decision.reason)
    }

    @Test
    fun rejectsEmptyPayload() {
        assertIs<PushWakePayloadPolicy.Decision.Reject>(
            PushWakePayloadPolicy.evaluate(emptyMap())
        )
    }
}
