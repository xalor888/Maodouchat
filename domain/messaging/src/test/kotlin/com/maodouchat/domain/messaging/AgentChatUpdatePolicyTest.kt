package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentChatUpdatePolicyTest {

    @Test
    fun `updateEntries parses known keys in fixed order and skips unknown values`() {
        assertEquals(
            listOf("pinned" to true, "archived" to false),
            AgentChatUpdatePolicy.updateEntries(mapOf("archived" to "false", "pinned" to "yes", "muted" to "maybe")),
        )
        assertEquals(
            listOf("muted" to true, "markedUnread" to false),
            AgentChatUpdatePolicy.updateEntries(mapOf("markedUnread" to "0", "muted" to "1")),
        )
    }

    @Test
    fun `updateEntries returns empty when nothing parses`() {
        assertTrue(AgentChatUpdatePolicy.updateEntries(emptyMap()).isEmpty())
        assertTrue(AgentChatUpdatePolicy.updateEntries(mapOf("pinned" to "maybe")).isEmpty())
    }

    @Test
    fun `formatUpdated joins entries in order`() {
        assertEquals(
            "Updated c1 pinned=true muted=false",
            AgentChatUpdatePolicy.formatUpdated("c1", listOf("pinned" to true, "muted" to false)),
        )
    }
}
