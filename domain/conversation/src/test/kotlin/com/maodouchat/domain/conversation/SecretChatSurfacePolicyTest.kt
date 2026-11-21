package com.maodouchat.domain.conversation

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 密聊 surface 纯集合单测：由 app 侧 SecretChatSessionTest 平移，
// 覆盖对象改为 SecretChatSurfacePolicy；行为与原来一致。
class SecretChatSurfacePolicyTest {
    @BeforeTest
    @AfterTest
    fun reset() {
        SecretChatSurfacePolicy.clearSurfaceMarkers()
    }

    @Test
    fun `clearSurfaceMarker drops marker without touching others`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.markSurfaceActive("b")
        SecretChatSurfacePolicy.clearSurfaceMarker("a")
        assertEquals(setOf("b"), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
        assertTrue(SecretChatSurfacePolicy.hasActiveSecretSurface())
    }

    @Test
    fun `duplicate mark is idempotent`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.markSurfaceActive("a")
        assertEquals(setOf("a"), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
    }

    @Test
    fun `clearSurfaceMarkersExcept with blank keep clears all`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.markSurfaceActive("b")
        SecretChatSurfacePolicy.clearSurfaceMarkersExcept("  ")
        assertEquals(emptySet<String>(), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
        assertFalse(SecretChatSurfacePolicy.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarkersExcept with null keep clears all`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.clearSurfaceMarkersExcept(null)
        assertFalse(SecretChatSurfacePolicy.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarkersExcept with unknown keep empties set`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.clearSurfaceMarkersExcept("ghost")
        assertEquals(emptySet<String>(), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
    }

    @Test
    fun `clearSurfaceMarkersExcept keeps concurrent notify id`() {
        SecretChatSurfacePolicy.markSurfaceActive("old")
        SecretChatSurfacePolicy.markSurfaceActive("keep")
        SecretChatSurfacePolicy.clearSurfaceMarkersExcept("keep")
        assertEquals(setOf("keep"), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
        assertTrue(SecretChatSurfacePolicy.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarker does not wipe other chats`() {
        SecretChatSurfacePolicy.markSurfaceActive("a")
        SecretChatSurfacePolicy.markSurfaceActive("b")
        SecretChatSurfacePolicy.clearSurfaceMarker("a")
        assertEquals(setOf("b"), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
    }

    @Test
    fun `blank ids are ignored`() {
        SecretChatSurfacePolicy.markSurfaceActive(" ")
        assertFalse(SecretChatSurfacePolicy.hasActiveSecretSurface())
    }

    @Test
    fun `clearSurfaceMarker does not imply media wipe and leaves others`() {
        SecretChatSurfacePolicy.markSurfaceActive("keep")
        SecretChatSurfacePolicy.markSurfaceActive("drop")
        SecretChatSurfacePolicy.clearSurfaceMarker("drop")
        SecretChatSurfacePolicy.clearSurfaceMarker("")
        assertEquals(setOf("keep"), SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
    }

    @Test
    fun `concurrent marks from several threads all land`() {
        val threads = (0 until 8).map { i ->
            Thread {
                repeat(50) {
                    SecretChatSurfacePolicy.markSurfaceActive("shared")
                    SecretChatSurfacePolicy.markSurfaceActive("t$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val expected = (0 until 8).map { "t$it" }.toSet() + "shared"
        assertEquals(expected, SecretChatSurfacePolicy.activeSecretSurfaceChatIds())
    }
}
