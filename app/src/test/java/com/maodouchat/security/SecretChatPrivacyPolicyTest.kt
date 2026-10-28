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
    fun `secret chat denies everything even when locked`() {
        val caps = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = true)

        PrivacyAction.values().forEach { action ->
            assertFalse(
                "Secret+locked should deny action $action",
                ConversationPrivacyPolicy.allows(caps, action)
            )
        }
    }

    @Test
    fun `privacy decision is stable across repeated calls`() {
        val caps = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)

        val first = PrivacyAction.values().map { ConversationPrivacyPolicy.allows(caps, it) }
        val second = PrivacyAction.values().map { ConversationPrivacyPolicy.allows(caps, it) }
        assertEquals(first, second)
    }

    @Test
    fun `onRead keeps armed state without timer`() {
        assertEquals(
            SecretChatState.ARMED,
            SecretChatStateMachine.onRead(SecretChatState.ARMED, hasTimer = false)
        )
    }

    @Test
    fun `expiring and destroyed are terminal on read`() {
        assertEquals(
            SecretChatState.EXPIRING,
            SecretChatStateMachine.onRead(SecretChatState.EXPIRING, hasTimer = true)
        )
        assertEquals(
            SecretChatState.EXPIRING,
            SecretChatStateMachine.onRead(SecretChatState.EXPIRING, hasTimer = false)
        )
        assertEquals(
            SecretChatState.DESTROYED,
            SecretChatStateMachine.onRead(SecretChatState.DESTROYED, hasTimer = false)
        )
    }

    @Test
    fun `onExpiry keeps expiring state`() {
        assertEquals(
            SecretChatState.EXPIRING,
            SecretChatStateMachine.onExpiry(SecretChatState.EXPIRING)
        )
    }

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

    @Test
    fun `privacy decision truth table covers all four capability combinations`() {
        val allAllowed = mapOf(
            PrivacyAction.SEARCH to true,
            PrivacyAction.FORWARD to true,
            PrivacyAction.EXPORT to true,
            PrivacyAction.AI to true,
            PrivacyAction.SCREENSHOT to true,
            PrivacyAction.NOTIFICATION_PREVIEW to true
        )
        val lockedOnly = allAllowed + (PrivacyAction.SEARCH to false)
        val allDenied = allAllowed.mapValues { false }
        listOf(
            Triple(false, false, allAllowed),
            Triple(false, true, lockedOnly),
            Triple(true, false, allDenied),
            Triple(true, true, allDenied)
        ).forEach { (isSecret, isLocked, expected) ->
            val caps = ConversationPrivacyCapabilities(isSecretChat = isSecret, isLocked = isLocked)
            expected.forEach { (action, verdict) ->
                assertEquals(
                    "$isSecret/$isLocked/$action",
                    verdict,
                    ConversationPrivacyPolicy.allows(caps, action)
                )
            }
        }
    }

    @Test
    fun `state machine full transition table`() {
        listOf(true, false).forEach { hasTimer ->
            val expectedActive = if (hasTimer) SecretChatState.ARMED else SecretChatState.ACTIVE
            assertEquals(expectedActive, SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer))
            assertEquals(SecretChatState.ARMED, SecretChatStateMachine.onRead(SecretChatState.ARMED, hasTimer))
            assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onRead(SecretChatState.EXPIRING, hasTimer))
            assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onRead(SecretChatState.DESTROYED, hasTimer))
        }
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onExpiry(SecretChatState.ARMED))
        assertEquals(SecretChatState.ACTIVE, SecretChatStateMachine.onExpiry(SecretChatState.ACTIVE))
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onExpiry(SecretChatState.EXPIRING))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onExpiry(SecretChatState.DESTROYED))
        SecretChatState.values().forEach { state ->
            assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onDestroy(state))
        }
    }

    @Test
    fun `capabilities equality is value based`() {
        assertEquals(
            ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false),
            ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)
        )
        assertFalse(
            ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false) ==
                ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)
        )
        assertEquals(
            ConversationPrivacyCapabilities(isSecretChat = true, isLocked = true),
            ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false).copy(isLocked = true)
        )
    }

    @Test
    fun `destroyed state ignores every transition`() {
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onRead(SecretChatState.DESTROYED, true))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onRead(SecretChatState.DESTROYED, false))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onExpiry(SecretChatState.DESTROYED))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onDestroy(SecretChatState.DESTROYED))
    }

    @Test
    fun `expiring state is terminal except destroy`() {
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onRead(SecretChatState.EXPIRING, true))
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onRead(SecretChatState.EXPIRING, false))
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onExpiry(SecretChatState.EXPIRING))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onDestroy(SecretChatState.EXPIRING))
    }

}
