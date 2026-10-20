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
    fun `markSurfaceInactive drops marker without touching others`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.markSurfaceActive("b")
        SecretChatSession.markSurfaceInactive("a")
        assertEquals(setOf("b"), SecretChatSession.activeSecretSurfaceChatIds())
        assertTrue(SecretChatSession.hasActiveSecretSurface())
    }

    @Test
    fun `duplicate mark is idempotent`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.markSurfaceActive("a")
        assertEquals(setOf("a"), SecretChatSession.activeSecretSurfaceChatIds())
    }

    @Test
    fun `clearSurfaceMarkersExcept with blank keep clears all`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.markSurfaceActive("b")
        SecretChatSession.clearSurfaceMarkersExcept("  ")
        assertEquals(emptySet<String>(), SecretChatSession.activeSecretSurfaceChatIds())
        assertFalse(SecretChatSession.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarkersExcept with null keep clears all`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.clearSurfaceMarkersExcept(null)
        assertFalse(SecretChatSession.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarkersExcept with unknown keep empties set`() {
        SecretChatSession.markSurfaceActive("a")
        SecretChatSession.clearSurfaceMarkersExcept("ghost")
        assertEquals(emptySet<String>(), SecretChatSession.activeSecretSurfaceChatIds())
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

    @Test
    fun `clearSurfaceMarker does not imply media wipe and leaves others`() {
        SecretChatSession.markSurfaceActive("keep")
        SecretChatSession.markSurfaceActive("drop")
        SecretChatSession.clearSurfaceMarker("drop")
        SecretChatSession.clearSurfaceMarker("")
        assertEquals(setOf("keep"), SecretChatSession.activeSecretSurfaceChatIds())
    }
}
