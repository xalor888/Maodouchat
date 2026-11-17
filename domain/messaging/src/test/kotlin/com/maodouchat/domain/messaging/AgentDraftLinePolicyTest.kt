package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentDraftLinePolicyTest {

    @Test
    fun `draftLine joins chat id and text with tab`() {
        assertEquals("c1\t你好", AgentDraftLinePolicy.draftLine("c1", "你好"))
    }

    @Test
    fun `draftLine truncates long text to 160 chars`() {
        val long = "草稿".repeat(100)
        assertEquals("c1\t${long.take(160)}", AgentDraftLinePolicy.draftLine("c1", long))
        assertEquals(160, AgentDraftLinePolicy.draftLine("c1", long).substringAfter("\t").length)
    }

    @Test
    fun `draftLine keeps short text intact`() {
        assertEquals("c9\t短", AgentDraftLinePolicy.draftLine("c9", "短"))
    }
}
