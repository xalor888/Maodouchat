package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `locked secret chat still hidden without session unlock`() {
        val lockedSecret = MessageVisibilityContext(
            isSecretChat = true,
            isChatLocked = true,
            isChatUnlockedInSession = false,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(
                MessageVisibilityPolicy.isVisible(scope, lockedSecret),
                "Locked secret chat should hide message in $scope"
            )
        }
    }

    @Test
    fun `session unlock reopens in-conversation scopes for locked secret chat`() {
        val unlockedSecret = MessageVisibilityContext(
            isSecretChat = true,
            isChatLocked = true,
            isChatUnlockedInSession = true,
        )

        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, unlockedSecret))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, unlockedSecret))
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, unlockedSecret))
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, unlockedSecret))
    }

    @Test
    fun `deleted beats session unlock everywhere`() {
        val deletedUnlocked = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = true,
            isChatUnlockedInSession = true,
            isDeleted = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(
                MessageVisibilityPolicy.isVisible(scope, deletedUnlocked),
                "Deleted message should be invisible in $scope even when unlocked"
            )
        }
    }

    @Test
    fun `revoked secret message stays invisible in conversation search`() {
        val revokedSecret = MessageVisibilityContext(
            isSecretChat = true,
            isChatLocked = false,
            isRevoked = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(
                MessageVisibilityPolicy.isVisible(scope, revokedSecret),
                "Revoked message should be invisible in $scope"
            )
        }
    }

    @Test
    fun `default context flags mean visible everywhere`() {
        val defaults = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertTrue(
                MessageVisibilityPolicy.isVisible(scope, defaults),
                "Default context should be visible in $scope"
            )
        }
    }

    @Test
    fun `session unlock flag without a lock changes nothing`() {
        val context = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
            isChatUnlockedInSession = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertTrue(MessageVisibilityPolicy.isVisible(scope, context), scope.name)
        }
    }

    @Test
    fun `session unlock flag does not lift the secret restriction`() {
        val context = MessageVisibilityContext(
            isSecretChat = true,
            isChatLocked = false,
            isChatUnlockedInSession = true,
        )

        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, context))
        assertFalse(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, context))
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, context))
        assertTrue(MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, context))
    }

    @Test
    fun `deleted and revoked together stay invisible everywhere`() {
        val context = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = false,
            isDeleted = true,
            isRevoked = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(MessageVisibilityPolicy.isVisible(scope, context), scope.name)
        }
    }

    @Test
    fun `revoked beats session unlock everywhere`() {
        val context = MessageVisibilityContext(
            isSecretChat = false,
            isChatLocked = true,
            isChatUnlockedInSession = true,
            isRevoked = true,
        )

        MessageVisibilityScope.entries.forEach { scope ->
            assertFalse(MessageVisibilityPolicy.isVisible(scope, context), scope.name)
        }
    }

    @Test
    fun `paired scopes always agree`() {
        listOf(
            Triple(false, false, false), Triple(true, false, false),
            Triple(false, true, false), Triple(true, true, false),
            Triple(false, false, true), Triple(true, false, true),
            Triple(false, true, true), Triple(true, true, true),
        ).forEach { (secret, locked, unlocked) ->
            val context = MessageVisibilityContext(
                isSecretChat = secret,
                isChatLocked = locked,
                isChatUnlockedInSession = unlocked,
            )
            assertEquals(
                MessageVisibilityPolicy.isVisible(MessageVisibilityScope.GLOBAL_SEARCH, context),
                MessageVisibilityPolicy.isVisible(MessageVisibilityScope.STARRED_LIST, context),
                "GLOBAL_SEARCH vs STARRED_LIST secret=$secret locked=$locked unlocked=$unlocked"
            )
            assertEquals(
                MessageVisibilityPolicy.isVisible(MessageVisibilityScope.CONVERSATION_SEARCH, context),
                MessageVisibilityPolicy.isVisible(MessageVisibilityScope.MEDIA_GALLERY, context),
                "CONVERSATION_SEARCH vs MEDIA_GALLERY secret=$secret locked=$locked unlocked=$unlocked"
            )
        }
    }
}
