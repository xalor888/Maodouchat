package com.maodouchat.security

import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.domain.messaging.ConversationPrivacyPolicy
import com.maodouchat.domain.messaging.PrivacyAction
import com.maodouchat.domain.messaging.SecretChatState
import com.maodouchat.domain.messaging.SecretChatStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretChatPrivacyPolicyTest {

    @Test
    fun `secret chat denies all privacy-sensitive actions`() {
        val secretCaps = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)

        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.SEARCH))
        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.FORWARD))
        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.EXPORT))
        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.AI))
        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.SCREENSHOT))
        assertFalse(ConversationPrivacyPolicy.allows(secretCaps, PrivacyAction.NOTIFICATION_PREVIEW))
    }

    @Test
    fun `locked chat denies search but allows other non-secret actions`() {
        val lockedCaps = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = true)

        assertFalse(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.SEARCH))
        assertTrue(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.FORWARD))
        assertTrue(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.EXPORT))
        assertTrue(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.AI))
        assertTrue(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.SCREENSHOT))
        assertTrue(ConversationPrivacyPolicy.allows(lockedCaps, PrivacyAction.NOTIFICATION_PREVIEW))
    }

    @Test
    fun `normal chat allows all actions`() {
        val normalCaps = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)

        PrivacyAction.values().forEach { action ->
            assertTrue(
                "Normal chat should allow action $action",
                ConversationPrivacyPolicy.allows(normalCaps, action)
            )
        }
    }

    @Test
    fun `state machine transitions on read`() {
        assertEquals(
            SecretChatState.ARMED,
            SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
        )
        assertEquals(
            SecretChatState.ACTIVE,
            SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = false)
        )
        assertEquals(
            SecretChatState.ARMED,
            SecretChatStateMachine.onRead(SecretChatState.ARMED, hasTimer = true)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onRead(SecretChatState.DESTROYED, hasTimer = true)
        )
    }

    @Test
    fun `state machine transitions on expiry`() {
        assertEquals(
            SecretChatState.EXPIRING,
            SecretChatStateMachine.onExpiry(SecretChatState.ARMED)
        )
        assertEquals(
            SecretChatState.ACTIVE,
            SecretChatStateMachine.onExpiry(SecretChatState.ACTIVE)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onExpiry(SecretChatState.DESTROYED)
        )
    }

    @Test
    fun `state machine transitions on destroy`() {
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onDestroy(SecretChatState.ACTIVE)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onDestroy(SecretChatState.ARMED)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onDestroy(SecretChatState.EXPIRING)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onDestroy(SecretChatState.DESTROYED)
        )
    }
}
