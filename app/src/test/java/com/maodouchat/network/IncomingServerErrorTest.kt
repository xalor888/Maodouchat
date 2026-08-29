package com.maodouchat.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingServerErrorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server error preserves control traffic retry delay`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow down","code":"CALL_INVITE_RATE_LIMITED","retryAfterSeconds":12}"""
        )

        assertEquals(12L, parsed.retryAfterSeconds)
        assertEquals("CALL_INVITE_RATE_LIMITED", parsed.code)
    }

    @Test
    fun `retired message id field is ignored`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":"FORBIDDEN","messageId":"m-42"}"""
        )

        assertEquals("denied", parsed.error)
        assertEquals("FORBIDDEN", parsed.code)
    }
}
