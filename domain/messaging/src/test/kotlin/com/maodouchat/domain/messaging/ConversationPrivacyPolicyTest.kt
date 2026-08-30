package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationPrivacyPolicyTest {

    @Test
    fun `secret chat blocks all sensitive actions`() {
        val secret = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)
        PrivacyAction.entries.forEach { action ->
            assertFalse(ConversationPrivacyPolicy.allows(secret, action), action.name)
        }
    }

    @Test
    fun `locked chat blocks search but allows forward`() {
        val locked = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = true)
        assertFalse(ConversationPrivacyPolicy.allows(locked, PrivacyAction.SEARCH))
        assertTrue(ConversationPrivacyPolicy.allows(locked, PrivacyAction.FORWARD))
    }

    @Test
    fun `normal chat allows all`() {
        val normal = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)
        PrivacyAction.entries.forEach { action ->
            assertTrue(ConversationPrivacyPolicy.allows(normal, action), action.name)
        }
    }

    @Test
    fun `secret chat state machine arms then expires`() {
        val armed = SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
        assertEquals(SecretChatState.ARMED, armed)
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onExpiry(armed))
    }
}
