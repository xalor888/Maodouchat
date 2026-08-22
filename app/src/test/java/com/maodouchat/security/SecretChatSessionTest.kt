package com.maodouchat.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecretChatSessionTest {
    @Before
    @After
    fun reset() {
        SecretChatSession.clearSurfaceMarkers()
    }

    @Test
    fun `clearSurfaceMarkersExcept keeps concurrent notify id`() {
        SecretChatSession.markSurfaceActive("old")
        SecretChatSession.markSurfaceActive("keep")
        SecretChatSession.clearSurfaceMarkersExcept("keep")
        assertEquals(setOf("keep"), SecretChatSession.activeSecretSurfaceChatIds())
        assertTrue(SecretChatSession.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarker does not wipe other chats`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.markSurfaceActive("b")
        SecretChatSession.clearSurfaceMarker("a")
        assertEquals(setOf("b"), SecretChatSession.activeSecretSurfaceChatIds())
    }

    @Test
    fun `blank ids are ignored`() {
        SecretChatSession.markSurfaceActive(" ")
        assertFalse(SecretChatSession.hasActiveSecretSurface())
    }
}
