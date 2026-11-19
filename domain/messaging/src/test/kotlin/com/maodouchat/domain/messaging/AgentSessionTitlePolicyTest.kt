package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSessionTitlePolicyTest {

    @Test
    fun `default title is the new conversation label`() {
        assertEquals("新对话", AgentSessionTitlePolicy.DEFAULT_TITLE)
    }

    @Test
    fun `derives title from first user message truncated at 24`() {
        val messages = listOf(
            AgentChatMessage(role = "assistant", content = "welcome"),
            AgentChatMessage(role = "user", content = "x".repeat(40)),
            AgentChatMessage(role = "user", content = "second")
        )
        assertEquals("x".repeat(24), AgentSessionTitlePolicy.deriveTitle(messages, "新对话"))
    }

    @Test
    fun `falls back to current title without user messages`() {
        val messages = listOf(AgentChatMessage(role = "assistant", content = "welcome"))
        assertEquals("旧标题", AgentSessionTitlePolicy.deriveTitle(messages, "旧标题"))
        assertEquals("旧标题", AgentSessionTitlePolicy.deriveTitle(emptyList(), "旧标题"))
    }

    @Test
    fun `empty first user content stays empty`() {
        val messages = listOf(AgentChatMessage(role = "user", content = ""))
        assertEquals("", AgentSessionTitlePolicy.deriveTitle(messages, "新对话"))
    }
}
