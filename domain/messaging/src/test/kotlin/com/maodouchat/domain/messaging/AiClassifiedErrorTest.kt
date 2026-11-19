package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiClassifiedErrorTest {

    @Test
    fun `classified error defaults persisted code to code`() {
        val error = AiClassifiedError(code = "RATE_LIMITED")
        assertEquals(null, error.retryAfterSeconds)
        assertEquals("RATE_LIMITED", error.persistedCode)
    }

    @Test
    fun `classified error carries retry timing`() {
        val error = AiClassifiedError(code = "QUOTA_EXCEEDED", retryAfterSeconds = 30L, persistedCode = "QUOTA_EXCEEDED:30")
        assertEquals(30L, error.retryAfterSeconds)
        assertEquals("QUOTA_EXCEEDED:30", error.persistedCode)
    }
}
