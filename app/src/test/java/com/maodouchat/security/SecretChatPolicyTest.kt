package com.maodouchat.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretChatPolicyTest {

    @Test
    fun secretChatTypeRecognized() {
        assertTrue(SecretChatPolicy.isSecretChatType("SECRET"))
        assertTrue(SecretChatPolicy.isSecretChatType("secret"))
        assertFalse(SecretChatPolicy.isSecretChatType("DIRECT"))
        assertFalse(SecretChatPolicy.isSecretChatType("GROUP"))
        assertFalse(SecretChatPolicy.isSecretChatType(null))
    }

    @Test
    fun onlyOrdinaryDirectCanStartSecret() {
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "DIRECT"))
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = null))
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = ""))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "SECRET"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = true, chatType = "GROUP"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = true, chatType = "DIRECT"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "CHANNEL"))
    }

    @Test
    fun secretCreateIsTwoPersonDirectOnly() {
        assertTrue(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 2))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 1))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = true, participantCount = 2))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = true, participantCount = 8))
    }

    @Test
    fun allChatsFolderHidesSecret() {
        assertTrue(SecretChatPolicy.excludeFromAllChats(isSecret = true))
        assertFalse(SecretChatPolicy.excludeFromAllChats(isSecret = false))
    }

    @Test
    fun isSecretChatTypeToleratesPaddingAndMixedCase() {
        assertTrue(SecretChatPolicy.isSecretChatType(" secret "))
        assertTrue(SecretChatPolicy.isSecretChatType("\tSECRET\n"))
        assertTrue(SecretChatPolicy.isSecretChatType("SeCrEt"))
        assertFalse(SecretChatPolicy.isSecretChatType("SECRETX"))
        assertFalse(SecretChatPolicy.isSecretChatType("SECRE"))
        assertFalse(SecretChatPolicy.isSecretChatType("  "))
    }

    @Test
    fun canStartFromDirectToleratesTypeFormatting() {
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = " direct "))
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "Direct"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "secret"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = true, chatType = null))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = true, chatType = ""))
    }

    @Test
    fun canCreateSecretRejectsNonTwoPerson() {
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 0))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 3))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = -1))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = true, participantCount = 2))
        assertTrue(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 2))
    }

    @Test
    fun folderPlacementTruthTableIsComplementary() {
        for (isSecret in listOf(true, false)) {
            assertEquals(isSecret, SecretChatPolicy.excludeFromAllChats(isSecret))
            assertEquals(!isSecret, SecretChatPolicy.allowInCustomOrLockedFolder(isSecret))
            assertEquals(isSecret, SecretChatPolicy.excludeFromAllChats(isSecret))
        }
    }

    @Test
    fun mosaicDisplayNameAlwaysFixedLabel() {
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("😀昵称"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("  张三  "))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("密聊"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName(null))
    }

    @Test
    fun mosaicHidesIdentity() {
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("张三"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("Alice"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("   "))
        assertFalse(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = true))
        assertTrue(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = false))
    }
}
