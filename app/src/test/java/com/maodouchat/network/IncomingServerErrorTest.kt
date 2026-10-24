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

    @Test
    fun `negative retry delay is not clamped`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow","retryAfterSeconds":-5}"""
        )

        assertEquals(-5L, parsed.retryAfterSeconds)
    }

    @Test
    fun `large retry delay survives as long`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow","retryAfterSeconds":9999999999}"""
        )

        assertEquals(9999999999L, parsed.retryAfterSeconds)
    }

    @Test
    fun `code only payload yields defaults`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"code":"FORBIDDEN"}"""
        )

        assertEquals("", parsed.error)
        assertEquals("FORBIDDEN", parsed.code)
        assertNull(parsed.retryAfterSeconds)
    }

    @Test
    fun `unknown array field is ignored`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","tags":["a","b"]}"""
        )

        assertEquals("denied", parsed.error)
    }

    @Test
    fun `surrounding whitespace in error text is preserved`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"  spaced  "}"""
        )

        assertEquals("  spaced  ", parsed.error)
    }

    @Test
    fun `error only payload yields null code and delay`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied"}"""
        )

        assertEquals("denied", parsed.error)
        assertNull(parsed.code)
        assertNull(parsed.retryAfterSeconds)
    }

    @Test
    fun `empty code string is preserved not null`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":""}"""
        )

        assertEquals("", parsed.code)
    }

    @Test
    fun `defaults round trip preserves nulls`() {
        val original = IncomingServerError()
        val roundTripped = json.decodeFromString(
            IncomingServerError.serializer(),
            json.encodeToString(IncomingServerError.serializer(), original)
        )

        assertEquals(original, roundTripped)
        assertNull(roundTripped.code)
        assertNull(roundTripped.retryAfterSeconds)
    }

    @Test
    fun `escaped quote in error text survives parsing`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"a\"b"}"""
        )

        assertEquals("a\"b", parsed.error)
    }

    @Test
    fun `max long retry delay survives`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow","retryAfterSeconds":9223372036854775807}"""
        )

        assertEquals(9223372036854775807L, parsed.retryAfterSeconds)
    }

    @Test
    fun `explicit null code and delay decode as null`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"slow","code":null,"retryAfterSeconds":null}"""
        )

        assertNull(parsed.code)
        assertNull(parsed.retryAfterSeconds)
    }

    @Test
    fun `newline escape in error text survives parsing`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"a\nb"}"""
        )

        assertEquals("a\nb", parsed.error)
    }

    @Test
    fun `one second retry delay round trips`() {
        val original = IncomingServerError(error = "slow", retryAfterSeconds = 1)
        val roundTripped = json.decodeFromString(
            IncomingServerError.serializer(),
            json.encodeToString(IncomingServerError.serializer(), original)
        )

        assertEquals(original, roundTripped)
    }

    @Test
    fun `code with underscores and digits is preserved`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":"ERR_429_X"}"""
        )

        assertEquals("ERR_429_X", parsed.code)
    }

    @Test
    fun `unknown boolean field is ignored`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","retried":true}"""
        )

        assertEquals("denied", parsed.error)
    }
}
