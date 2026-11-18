package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AgentToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionModelsTest {

    @Test
    fun `approval keeps call and preview`() {
        val call = AgentToolCall("c1", "search", "{}")
        val pending = PendingAgentApproval(call, "preview")
        assertEquals("c1", pending.call.id)
        assertEquals("preview", pending.preview)
    }

    @Test
    fun `turn event variants carry payload`() {
        val events: List<AgentTurnEvent> = listOf(
            AgentTurnEvent.TextDelta("hi"),
            AgentTurnEvent.ToolStarted("search"),
            AgentTurnEvent.NeedsApproval(
                PendingAgentApproval(AgentToolCall("c1", "search", "{}"), "p")
            ),
            AgentTurnEvent.Failed("boom")
        )
        assertEquals(4, events.size)
        assertTrue(events[2] is AgentTurnEvent.NeedsApproval)
        assertEquals("hi", (events[0] as AgentTurnEvent.TextDelta).text)
    }
}
