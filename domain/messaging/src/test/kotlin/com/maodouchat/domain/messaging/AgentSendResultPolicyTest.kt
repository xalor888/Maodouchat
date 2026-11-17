package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSendResultPolicyTest {

    @Test
    fun `queued result distinguishes group and direct chats`() {
        assertEquals("Queued m_1 to group c1 via E2EE outbox", AgentSendResultPolicy.queued("m_1", true, "c1"))
        assertEquals("Queued m_1 to direct c1 via E2EE outbox", AgentSendResultPolicy.queued("m_1", false, "c1"))
    }

    @Test
    fun `draft results carry chat id and char count`() {
        assertEquals("Cleared draft for c1", AgentSendResultPolicy.draftCleared("c1"))
        assertEquals("Saved draft for c1 (12 chars)", AgentSendResultPolicy.draftSaved("c1", 12))
    }
}
