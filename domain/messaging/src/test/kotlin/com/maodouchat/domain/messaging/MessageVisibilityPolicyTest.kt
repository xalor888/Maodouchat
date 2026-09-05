package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageVisibilityPolicyTest {

    @Test
    fun `deleted or revoked message is invisible across all scopes`() {
        val deletedContext = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
            isDeleted = true,
        )
        val revokedContext = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
            isRevoked = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(
                MessageVisibilityPolicy.isVisible(scope, deletedContext),
                "Deleted message should be invisible in $scope"
            )
            assertFalse(
                MessageVisibilityPolicy.isVisible(scope, revokedContext),
                "Revoked message should be invisible in $scope"
            )
        }
    }

    @Test
    fun `secret chat messages are blocked from global search and starred list`() {
        val secretContext = MessageVisibilityContext(
            isSecretChat = true,
            isChatLocked = false,
        )

        assertFalse(
            MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, secretContext),
            "Secret chat must not leak in global search"
        )
        assertFalse(
            MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, secretContext),
            "Secret chat must not leak in global starred list"
        )
        assertTrue(
            MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, secretContext),
            "Secret chat allows in-conversation search"
        )
        assertTrue(
            MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, secretContext),
            "Secret chat media gallery allowed when inside session"
        )
    }

    @Test
    fun `locked chat messages are hidden unless unlocked in session`() {
        val lockedContext = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = true,
            isChatUnlockedInSession = false,
        )

        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, lockedContext))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, lockedContext))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, lockedContext))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, lockedContext))

        val unlockedContext = lockedContext.copy(isChatUnlockedInSession = true)
        // Global search & Starred list still exclude locked chats globally by design
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, unlockedContext))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, unlockedContext))
        // But in-session conversation search & media gallery are unlocked
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, unlockedContext))
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, unlockedContext))
    }

    @Test
    fun `normal active chat message is visible in all scopes`() {
        val normalContext = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertTrue(
                MessageVisibilityPolicy.isVisible(scope, normalContext),
                "Normal message should be visible in $scope"
            )
        }
    }
}
