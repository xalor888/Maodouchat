package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretChatDisplayPolicyTest {

    @Test
    fun allChatsFolderHidesSecret() {
        assertTrue(SecretChatDisplayPolicy.excludeFromAllChats(isSecret = true))
        assertFalse(SecretChatDisplayPolicy.excludeFromAllChats(isSecret = false))
    }

    @Test
    fun folderPlacementTruthTableIsComplementary() {
        for (isSecret in listOf(true, false)) {
            assertEquals(isSecret, SecretChatDisplayPolicy.excludeFromAllChats(isSecret))
            assertEquals(isSecret, SecretChatDisplayPolicy.excludeFromAllChats(isSecret))
        }
    }

    @Test
    fun mosaicDisplayNameAlwaysFixedLabel() {
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("😀昵称"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("  张三  "))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("密聊"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName(null))
    }

    @Test
    fun mosaicHidesIdentity() {
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("张三"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("Alice"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("   "))
    }

    @Test
    fun mosaicDisplayNameIgnoresEmptyAndVeryLongInput() {
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName(""))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("x".repeat(500)))
    }
}
