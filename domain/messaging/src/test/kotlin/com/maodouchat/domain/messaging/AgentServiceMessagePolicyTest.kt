package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentServiceMessagePolicyTest {

    @Test
    fun `reject tool names the tool`() {
        assertEquals(
            "用户拒绝了 send_text_message",
            AgentServiceMessagePolicy.rejectTool("send_text_message")
        )
    }

    @Test
    fun `stored tool result truncates at 500`() {
        val long = "x".repeat(600)
        assertEquals(500, AgentServiceMessagePolicy.toolResultStored(long).length)
        assertEquals("ok", AgentServiceMessagePolicy.toolResultStored("ok"))
    }

    @Test
    fun `fixed prompts are non-blank`() {
        assertTrue(AgentServiceMessagePolicy.AI_NOT_ENABLED.isNotBlank())
        assertTrue(AgentServiceMessagePolicy.MISSING_PROVIDER.contains("AI 与隐私"))
    }
}
