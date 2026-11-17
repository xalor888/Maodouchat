package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentEntityIdPolicyTest {

    @Test
    fun `taskId prefixes with task_`() {
        assertEquals("task_abc", AgentEntityIdPolicy.taskId("abc"))
    }

    @Test
    fun `messageId prefixes with m_`() {
        assertEquals("m_abc", AgentEntityIdPolicy.messageId("abc"))
    }

    @Test
    fun `providerId prefixes with p_`() {
        assertEquals("p_abc", AgentEntityIdPolicy.providerId("abc"))
    }

    @Test
    fun `ids keep the uuid suffix intact`() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        assertTrue(AgentEntityIdPolicy.taskId(uuid).endsWith(uuid))
        assertTrue(AgentEntityIdPolicy.messageId(uuid).endsWith(uuid))
        assertTrue(AgentEntityIdPolicy.providerId(uuid).endsWith(uuid))
    }
}
