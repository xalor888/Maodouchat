package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AgentChatMessage
import com.maodouchat.core.model.AgentSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSessionCodecTest {

    @Test
    fun `session round trip keeps id title createdAt and messages`() {
        val session = AgentSession(
            id = "s1",
            title = "hello",
            createdAt = 1_700_000_000_000L,
            messages = listOf(
                AgentChatMessage(role = "user", content = "hi"),
                AgentChatMessage(role = "assistant", content = "hello"),
                AgentChatMessage(role = "tool", content = "done", toolCallId = "c1", toolName = "search")
            )
        )
        val decoded = AgentSessionCodec.parseSessions(AgentSessionCodec.encodeSessions(listOf(session)))
        assertEquals(listOf(session), decoded)
    }

    @Test
    fun `parse sessions drops blank id rows and defaults blank title`() {
        val raw = """[{"id":"  ","title":"x","messages":[]},{"id":" s1 ","title":"","messages":[]}]"""
        val decoded = AgentSessionCodec.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals("s1", decoded[0].id)
        assertEquals("新对话", decoded[0].title)
    }

    @Test
    fun `parse sessions returns empty list for non-array json`() {
        assertTrue(AgentSessionCodec.parseSessions("not json").isEmpty())
        assertTrue(AgentSessionCodec.parseSessions("{}").isEmpty())
        assertTrue(AgentSessionCodec.parseSessions("").isEmpty())
    }

    @Test
    fun `encode sessions skips blank tool rows and keeps non-blank ones in order`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 0L,
            messages = listOf(
                AgentChatMessage(role = "tool", content = "", toolCallId = "c0", toolName = "x"),
                AgentChatMessage(role = "tool", content = "first", toolCallId = "c1", toolName = "a"),
                AgentChatMessage(role = "tool", content = "second", toolCallId = "c2", toolName = "b")
            )
        )
        val decoded = AgentSessionCodec.parseSessions(AgentSessionCodec.encodeSessions(listOf(session)))
        assertEquals(listOf("first", "second"), decoded[0].messages.map { it.content })
    }

    @Test
    fun `parse sessions maps blank toolCallId and toolName to null`() {
        val raw = """[{"id":"s1","title":"t","createdAt":5,"messages":[{"role":"tool","content":"ok","toolCallId":"","toolName":"  "}]}]"""
        val decoded = AgentSessionCodec.parseSessions(raw)
        assertEquals(1, decoded[0].messages.size)
        assertEquals(null, decoded[0].messages[0].toolCallId)
        assertEquals(null, decoded[0].messages[0].toolName)
    }

    @Test
    fun `parse sessions skips non-object message rows`() {
        val raw = """[{"id":"s1","title":"t","messages":["oops",42,{"role":"user","content":"hi"}]}]"""
        val decoded = AgentSessionCodec.parseSessions(raw)
        assertEquals(listOf("hi"), decoded[0].messages.map { it.content })
    }

    @Test
    fun `parse sessions trims id and defaults missing createdAt to zero`() {
        val raw = """[{"id":"  s1  ","title":"t","messages":[]}]"""
        val decoded = AgentSessionCodec.parseSessions(raw)
        assertEquals("s1", decoded[0].id)
        assertEquals(0L, decoded[0].createdAt)
    }

    @Test
    fun `retain recent keeps last 12 sessions`() {
        val sessions = (1..15).map { AgentSession(id = "s$it", title = "t$it", createdAt = it.toLong(), messages = emptyList()) }
        val kept = AgentSessionCodec.retainRecent(sessions)
        assertEquals(12, kept.size)
        assertEquals("s4", kept.first().id)
        assertEquals("s15", kept.last().id)
        assertEquals(sessions.take(5), AgentSessionCodec.retainRecent(sessions.take(5)))
    }
}
