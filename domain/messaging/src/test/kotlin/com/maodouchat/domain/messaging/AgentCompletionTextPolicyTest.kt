package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentCompletionTextPolicyTest {

    @Test
    fun `short content passes through`() {
        assertEquals("hello", AgentCompletionTextPolicy.capUserContent("hello"))
    }

    @Test
    fun `long content caps at MAX_USER_CONTENT_CHARS`() {
        val long = "x".repeat(AgentCompletionTextPolicy.MAX_USER_CONTENT_CHARS + 1)
        assertEquals(AgentCompletionTextPolicy.MAX_USER_CONTENT_CHARS, AgentCompletionTextPolicy.capUserContent(long).length)
    }

    @Test
    fun `result is trimmed`() {
        assertEquals("hello", AgentCompletionTextPolicy.trimResult("  hello\n"))
    }

    @Test
    fun `inner whitespace is kept`() {
        assertEquals("a  b", AgentCompletionTextPolicy.trimResult("  a  b  "))
    }
}
