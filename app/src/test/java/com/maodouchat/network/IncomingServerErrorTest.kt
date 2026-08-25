package com.maodouchat.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingServerErrorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server error parses optional message id`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":"FORBIDDEN","messageId":"m-42"}"""
        )

        assertEquals("m-42", parsed.messageId)
        assertEquals("FORBIDDEN", parsed.code)
    }

    @Test
    fun `legacy server error remains compatible without message id`() {
        val parsed = json.decodeFromString(
            IncomingServerError.serializer(),
            """{"error":"denied","code":"FORBIDDEN"}"""
        )

        assertNull(parsed.messageId)
    }
}
