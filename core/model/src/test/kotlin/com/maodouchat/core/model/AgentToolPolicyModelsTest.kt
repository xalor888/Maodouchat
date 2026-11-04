package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentToolPolicyModelsTest {

    @Test
    fun `risk levels are all distinct`() {
        assertEquals(3, Risk.values().size)
        assertNotEquals(Risk.READ, Risk.WRITE)
        assertNotEquals(Risk.WRITE, Risk.SEND)
    }

    @Test
    fun `approval outcomes are all distinct`() {
        assertEquals(3, Approval.values().size)
        assertNotEquals(Approval.ALLOW, Approval.NEED_USER)
        assertNotEquals(Approval.NEED_USER, Approval.DENY)
    }

    @Test
    fun `tool spec carries fields and risk`() {
        val spec = ToolSpec(
            name = "list_chats",
            description = "List chats",
            parameters = mapOf("limit" to Parameter("integer", "Max rows")),
            required = listOf(),
            risk = Risk.READ,
        )
        assertEquals("list_chats", spec.name)
        assertEquals(Risk.READ, spec.risk)
        assertEquals("integer", spec.parameters["limit"]?.type)
    }
}
