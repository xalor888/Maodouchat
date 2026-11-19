package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class SanitizedContextLineTest {

    @Test
    fun `sanitized line keeps its fields and copies`() {
        val line = SanitizedContextLine(sender = "user", text = "hello")
        assertEquals("user", line.sender)
        assertEquals("hello", line.text)
        assertEquals(line, line.copy(text = "hello"))
    }
}
