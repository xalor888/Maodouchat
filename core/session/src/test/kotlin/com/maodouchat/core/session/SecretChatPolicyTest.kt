package com.maodouchat.core.session

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

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
            assertEquals(!isSecret, SecretChatPolicy.allowInCustomOrLockedFolder(isSecret))
        }
    }

    @Test
    fun mosaicHidesIdentity() {
        assertFalse(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = true))
        assertTrue(SecretChatPolicy.allowInCustomOrLockedFolder(isSecret = false))
    }

    @Test
    fun chatTypeConstantPinsTheSecretMarker() {
        assertEquals("SECRET", SecretChatPolicy.CHAT_TYPE)
        assertTrue(SecretChatPolicy.isSecretChatType(SecretChatPolicy.CHAT_TYPE))
    }

    @Test
    fun canStartFromWhitespaceOnlyTypeButNotFromGroupSecret() {
        assertTrue(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "   "))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = true, chatType = "SECRET"))
    }

    @Test
    fun canCreateSecretRejectsGroupOfOneAndHugeNonGroup() {
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = true, participantCount = 1))
        assertFalse(SecretChatPolicy.canCreateSecret(isGroup = false, participantCount = 100))
    }

    @Test
    fun typeMatchingRejectsInnerSpacesAndNearMissDirect() {
        assertFalse(SecretChatPolicy.isSecretChatType("SEC RET"))
        assertFalse(SecretChatPolicy.canStartFromDirect(isGroup = false, chatType = "DIRECTX"))
    }
}
