package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentReadGatePolicyTest {

    @Test
    fun `blank chat id error`() {
        assertEquals("Error: chatId required", AgentReadGatePolicy.blankChatIdError())
    }

    @Test
    fun `secret chat error`() {
        assertEquals("Error: secret chats cannot be accessed by AI", AgentReadGatePolicy.secretChatError())
    }

    @Test
    fun `chat not found error`() {
        assertEquals("Error: chat not found", AgentReadGatePolicy.chatNotFoundError())
    }

    @Test
    fun `blank query error`() {
        assertEquals("Error: query required", AgentReadGatePolicy.blankQueryError())
    }

    @Test
    fun `blank post id error`() {
        assertEquals("Error: postId required", AgentReadGatePolicy.blankPostIdError())
    }

    @Test
    fun `blocked ids union secret and locked without duplicates`() {
        assertEquals(
            setOf("c1", "c2", "c3"),
            AgentReadGatePolicy.blockedIds(setOf("c1", "c2"), setOf("c2", "c3"))
        )
    }
}
