package com.maodouchat.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `empty payload yields defaults`() {
        val parsed = json.decodeFromString(IncomingServerError.serializer(), "{}")

        assertEquals("", parsed.error)
        assertNull(parsed.code)
        assertNull(parsed.retryAfterSeconds)
    }

    @Test
    fun `zero retry delay is preserved`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow","retryAfterSeconds":0}"""
        )

        assertEquals(0L, parsed.retryAfterSeconds)
    }

    @Test
    fun `unknown nested object is ignored`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":"FORBIDDEN","meta":{"a":1}}"""
        )

        assertEquals("denied", parsed.error)
        assertEquals("FORBIDDEN", parsed.code)
    }

    @Test
    fun `round trip preserves all fields`() {
        val original = IncomingServerError(
            error = "slow down",
            code = "RATE_LIMITED",
            retryAfterSeconds = 30
        )
        val roundTripped = json.decodeFromString(
            IncomingServerError.serializer(),
            json.encodeToString(IncomingServerError.serializer(), original)
        )

        assertEquals(original, roundTripped)
    }

    @Test
    fun `unicode error text survives parsing`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"请求过于频繁","code":"RATE_LIMITED"}"""
        )

        assertEquals("请求过于频繁", parsed.error)
    }
}
