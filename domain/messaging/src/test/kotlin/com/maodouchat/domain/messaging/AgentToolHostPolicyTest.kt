package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentToolHostPolicyTest {

    @Test
    fun `unknown tool error names the tool`() {
        assertEquals(
            "Error: unknown tool send_text_message",
            AgentToolHostPolicy.unknownTool("send_text_message")
        )
    }

    @Test
    fun `preview fallback truncates arguments at 160`() {
        assertEquals(
            "get_chat_history ${"x".repeat(160)}",
            AgentToolHostPolicy.previewFallback("get_chat_history", "x".repeat(200))
        )
        assertEquals(
            "get_chat_history {}",
            AgentToolHostPolicy.previewFallback("get_chat_history", "{}")
        )
    }

    @Test
    fun `result truncates at max tool result chars`() {
        val long = "x".repeat(6_500)
        assertEquals(6_000, AgentToolHostPolicy.truncateResult(long).length)
        assertEquals("ok", AgentToolHostPolicy.truncateResult("ok"))
    }

    @Test
    fun `fixed error strings`() {
        assertEquals("Error: not signed in", AgentToolHostPolicy.NOT_SIGNED_IN)
        assertEquals(
            "Error: rewrite_text is handled by the engine, not the host",
            AgentToolHostPolicy.REWRITE_HANDLED_BY_ENGINE
        )
    }
}
