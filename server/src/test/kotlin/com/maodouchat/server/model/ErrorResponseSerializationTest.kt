package com.maodouchat.server.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorResponseSerializationTest {

    @Test
    fun `message id round trips while legacy payload remains compatible`() {
        val encoded = Json.encodeToString(
            ErrorResponse(error = "send rejected", messageId = "m_failed")
        )

        assertTrue(encoded.contains("\"messageId\":\"m_failed\""))
        assertEquals("m_failed", Json.decodeFromString<ErrorResponse>(encoded).messageId)
        assertNull(Json.decodeFromString<ErrorResponse>("{\"error\":\"legacy\"}").messageId)
    }
}
