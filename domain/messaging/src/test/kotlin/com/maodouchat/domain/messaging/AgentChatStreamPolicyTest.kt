package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentChatStreamPolicyTest {

    @Test
    fun `streams only when enabled with a delta sink and no tools`() {
        assertTrue(AgentChatStreamPolicy.shouldStream(true, true, null))
        assertTrue(AgentChatStreamPolicy.shouldStream(true, true, emptyList<Any>()))
    }

    @Test
    fun `no stream when the provider disables it`() {
        assertFalse(AgentChatStreamPolicy.shouldStream(false, true, null))
    }

    @Test
    fun `no stream without a delta sink`() {
        assertFalse(AgentChatStreamPolicy.shouldStream(true, false, null))
    }

    @Test
    fun `no stream when tools are present`() {
        assertFalse(
            AgentChatStreamPolicy.shouldStream(true, true, listOf(mapOf("type" to "function"))),
        )
    }
}
