package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSecretGatePolicyTest {

    @Test
    fun secretAlwaysDeniedEvenIfUnlocked() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = false, unlocked = true)
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = true, isLocked = false, unlocked = true))
    }

    @Test
    fun pinLockedDeniedUntilUnlocked() {
        assertEquals(
            AgentSecretGatePolicy.PIN_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = false)
        )
        assertNull(
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = true)
        )
        assertTrue(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = true))
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = false))
    }

    @Test
    fun ordinaryChatAllowed() {
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, false))
        assertTrue(AgentSecretGatePolicy.includeInChatList(false, false, false))
    }
}
