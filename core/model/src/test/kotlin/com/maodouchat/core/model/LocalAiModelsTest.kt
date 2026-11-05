package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiModelsTest {

    @Test
    fun `chat message keeps tool call defaults`() {
        val msg = AgentChatMessage("user", "hello")
        assertEquals("user", msg.role)
        assertNull(msg.toolCallId)
        assertTrue(msg.toolCalls.isEmpty())
        assertEquals("assistant", msg.copy(role = "assistant").role)
    }

    @Test
    fun `session keeps messages in order`() {
        val session = AgentSession(
            "s1", "chat", 1000L,
            listOf(AgentChatMessage("user", "hi"))
        )
        assertEquals(1, session.messages.size)
        assertEquals("hi", session.messages[0].content)
    }

    @Test
    fun `overlay mode has three values`() {
        assertEquals(3, AgentOverlayMode.entries.size)
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayMode.valueOf("DISABLED"))
    }
}
