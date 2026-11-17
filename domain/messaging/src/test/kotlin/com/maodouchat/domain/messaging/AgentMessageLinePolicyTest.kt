package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentMessageLinePolicyTest {

    private fun message(content: String, timestamp: Long = 123L) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = content,
        type = MessageType.TEXT,
        timestamp = timestamp,
    )

    @Test
    fun `formatMessage joins timestamp sender type and sanitized text`() {
        assertEquals("123\tu1\tTEXT\thello", AgentMessageLinePolicy.formatMessage(message("hello")))
        assertEquals(
            "123\tu1\tTEXT\t" + "x".repeat(500),
            AgentMessageLinePolicy.formatMessage(message("x".repeat(600))),
        )
    }

    @Test
    fun `searchHitLine truncates searchable text at 160`() {
        assertEquals("c1\tm1\thello", AgentMessageLinePolicy.searchHitLine("c1", "m1", "hello"))
        assertEquals(
            "c1\tm1\t" + "x".repeat(160),
            AgentMessageLinePolicy.searchHitLine("c1", "m1", "x".repeat(200)),
        )
    }

    @Test
    fun `starredLine sanitizes content at 160`() {
        assertEquals("c1\tm1\thello", AgentMessageLinePolicy.starredLine("c1", "m1", "hello"))
        assertEquals(
            "c1\tm1\t" + "x".repeat(160),
            AgentMessageLinePolicy.starredLine("c1", "m1", "x".repeat(200)),
        )
    }

    @Test
    fun `searchLimit clamps to 1 to 20`() {
        assertEquals(1, AgentMessageLinePolicy.searchLimit(0))
        assertEquals(1, AgentMessageLinePolicy.searchLimit(1))
        assertEquals(20, AgentMessageLinePolicy.searchLimit(20))
        assertEquals(20, AgentMessageLinePolicy.searchLimit(21))
    }

    @Test
    fun `starredLimit clamps to 1 to 40`() {
        assertEquals(1, AgentMessageLinePolicy.starredLimit(0))
        assertEquals(1, AgentMessageLinePolicy.starredLimit(1))
        assertEquals(40, AgentMessageLinePolicy.starredLimit(40))
        assertEquals(40, AgentMessageLinePolicy.starredLimit(41))
    }
}
