package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEntryModelsTest {

    @Test
    fun `sanitized line keeps its fields and copies`() {
        val line = SanitizedContextLine(sender = "user", text = "hello")
        assertEquals("user", line.sender)
        assertEquals("hello", line.text)
        assertEquals(line, line.copy(text = "hello"))
    }
}
