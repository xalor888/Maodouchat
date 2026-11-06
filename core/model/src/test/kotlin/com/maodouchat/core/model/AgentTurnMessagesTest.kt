package com.maodouchat.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnMessagesTest {

    private val provider = LocalAiProvider(
        id = "p", name = "n", baseUrl = "https://example.invalid",
        apiKey = "k", model = "m", historyMessageLimit = 2
    )

    @Test
    fun `base assembles system history and user`() {
        val history = listOf(
            AgentChatMessage(role = "system", content = "old system"),
            AgentChatMessage(role = "user", content = "hi"),
            AgentChatMessage(role = "assistant", content = "hello")
        )
        val working = AgentTurnMessages.base(provider, history, "question", null)
        assertEquals(4, working.size)
        assertEquals("system", working[0].role)
        assertTrue(working[0].content.isNotBlank())
        assertEquals("user", working[1].role)
        assertEquals("assistant", working[2].role)
        assertEquals("user", working[3].role)
        assertEquals("question", working[3].content)
    }

    @Test
    fun `base truncates user text at 4000 chars`() {
        val working = AgentTurnMessages.base(provider, emptyList(), "x".repeat(4001), null)
        assertEquals(2, working.size)
        assertEquals(4000, working[1].content.length)
    }

    @Test
    fun `prefillApprovedCall appends assistant and tool messages`() = runBlocking {
        val working = mutableListOf(AgentChatMessage(role = "user", content = "do it"))
        val call = AgentToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val event = AgentTurnMessages.prefillApprovedCall(working, call) { _, _ -> "result!" }
        assertEquals(AgentTurnEvent.ToolFinished("search", "result!"), event)
        assertEquals(3, working.size)
        assertEquals("assistant", working[1].role)
        assertEquals(listOf(call), working[1].toolCalls)
        assertEquals("tool", working[2].role)
        assertEquals("result!", working[2].content)
        assertEquals("c1", working[2].toolCallId)
    }
}
