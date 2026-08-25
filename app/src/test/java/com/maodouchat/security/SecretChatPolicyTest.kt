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
    fun mosaicHidesIdentity() {
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("张三"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("Alice"))
        assertEquals("密聊", SecretChatPolicy.mosaicDisplayName("   "))
        assertFalse(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = true))
        assertTrue(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = false))
    }
}
