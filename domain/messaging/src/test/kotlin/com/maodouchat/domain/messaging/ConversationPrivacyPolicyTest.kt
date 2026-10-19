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

    @Test
    fun `locked chat allows every action except search`() {
        val locked = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = true)
        assertFalse(ConversationPrivacyPolicy.allows(locked, PrivacyAction.SEARCH))
        listOf(
            PrivacyAction.FORWARD,
            PrivacyAction.EXPORT,
            PrivacyAction.AI,
            PrivacyAction.SCREENSHOT,
            PrivacyAction.NOTIFICATION_PREVIEW,
        ).forEach { action ->
            assertTrue(ConversationPrivacyPolicy.allows(locked, action), action.name)
        }
    }

    @Test
    fun `secret and locked together blocks everything`() {
        val both = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = true)
        PrivacyAction.entries.forEach { action ->
            assertFalse(ConversationPrivacyPolicy.allows(both, action), action.name)
        }
    }

    @Test
    fun `onRead without timer keeps active, non-active states are untouched`() {
        assertEquals(SecretChatState.ACTIVE, SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = false))
        listOf(SecretChatState.ARMED, SecretChatState.EXPIRING, SecretChatState.DESTROYED).forEach { state ->
            assertEquals(state, SecretChatStateMachine.onRead(state, hasTimer = true))
            assertEquals(state, SecretChatStateMachine.onRead(state, hasTimer = false))
        }
    }

    @Test
    fun `onExpiry only fires on armed`() {
        assertEquals(SecretChatState.EXPIRING, SecretChatStateMachine.onExpiry(SecretChatState.ARMED))
        listOf(SecretChatState.ACTIVE, SecretChatState.EXPIRING, SecretChatState.DESTROYED).forEach { state ->
            assertEquals(state, SecretChatStateMachine.onExpiry(state))
        }
    }

    @Test
    fun `onDestroy collapses every state`() {
        SecretChatState.entries.forEach { state ->
            assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onDestroy(state))
        }
        val lifecycle = SecretChatStateMachine.onDestroy(
            SecretChatStateMachine.onExpiry(
                SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
            )
        )
        assertEquals(SecretChatState.DESTROYED, lifecycle)
    }

    @Test
    fun `allows truth table is pinned for all 24 combinations`() {
        listOf(false to false, true to false, false to true, true to true).forEach { (secret, locked) ->
            val caps = ConversationPrivacyCapabilities(isSecretChat = secret, isLocked = locked)
            assertEquals(
                !secret && !locked,
                ConversationPrivacyPolicy.allows(caps, PrivacyAction.SEARCH),
                "SEARCH secret=$secret locked=$locked"
            )
            val restExpected = !secret
            listOf(
                PrivacyAction.FORWARD,
                PrivacyAction.EXPORT,
                PrivacyAction.AI,
                PrivacyAction.SCREENSHOT,
                PrivacyAction.NOTIFICATION_PREVIEW,
            ).forEach { action ->
                assertEquals(
                    restExpected,
                    ConversationPrivacyPolicy.allows(caps, action),
                    "$action secret=$secret locked=$locked"
                )
            }
        }
    }

    @Test
    fun `onDestroy wins over a pending expiry`() {
        val armed = SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
        assertEquals(SecretChatState.ARMED, armed)
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onExpiry(SecretChatStateMachine.onDestroy(armed)))
        assertEquals(SecretChatState.DESTROYED, SecretChatStateMachine.onDestroy(SecretChatStateMachine.onDestroy(armed)))
    }

    @Test
    fun `arming twice stays armed`() {
        val armed = SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
        assertEquals(SecretChatState.ARMED, SecretChatStateMachine.onRead(armed, hasTimer = true))
        assertEquals(SecretChatState.ARMED, SecretChatStateMachine.onRead(armed, hasTimer = false))
    }

    @Test
    fun `expiry never touches a chat that was never armed`() {
        val active = SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = false)
        assertEquals(SecretChatState.ACTIVE, active)
        assertEquals(SecretChatState.ACTIVE, SecretChatStateMachine.onExpiry(active))
    }

    @Test
    fun `destroy then expiry stays destroyed`() {
        SecretChatState.entries.forEach { state ->
            assertEquals(
                SecretChatState.DESTROYED,
                SecretChatStateMachine.onExpiry(SecretChatStateMachine.onDestroy(state)),
                state.name
            )
        }
    }
}
