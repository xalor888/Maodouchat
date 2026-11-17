package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSecretGatePolicyTest {

    @Test
    fun `secret is denied even when unlocked`() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = false, unlocked = true),
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = true, isLocked = false, unlocked = true))
    }

    @Test
    fun `pin locked chat is denied until unlocked`() {
        assertEquals(
            AgentSecretGatePolicy.PIN_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = false),
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = false))
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = true))
        assertTrue(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = true))
    }
}
