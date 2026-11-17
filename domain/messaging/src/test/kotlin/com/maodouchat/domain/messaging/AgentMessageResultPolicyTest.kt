package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentMessageResultPolicyTest {

    @Test
    fun `star results carry starred flag and message id`() {
        assertEquals("Already starred=true", AgentMessageResultPolicy.alreadyStarred(true))
        assertEquals("Starred=false for m_1", AgentMessageResultPolicy.starred(false, "m_1"))
        assertEquals(
            "Starred=true locally for m_1 (offline)",
            AgentMessageResultPolicy.starredLocally(true, "m_1")
        )
    }

    @Test
    fun `server star mismatch reports server state`() {
        assertEquals("Error: server star state is false", AgentMessageResultPolicy.serverStarMismatch(false))
    }

    @Test
    fun `delete and revoke results carry message id`() {
        assertEquals("Deleted local message m_1", AgentMessageResultPolicy.deletedLocal("m_1"))
        assertEquals("Revoked m_1", AgentMessageResultPolicy.revoked("m_1"))
    }

    @Test
    fun `reaction result carries count`() {
        assertEquals("Reaction updated on m_1 count=3", AgentMessageResultPolicy.reactionUpdated("m_1", 3))
    }

    @Test
    fun `pin result carries pinned flag and message id`() {
        assertEquals("Pinned=true for m_1", AgentMessageResultPolicy.pinned(true, "m_1"))
    }
}
