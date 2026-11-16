package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiEntryVisibilityPolicyTest {

    @Test
    fun `surfaces show only when all four flags hold`() {
        assertTrue(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = true,
            userEnabled = true, masterEnabled = true))
        assertFalse(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = false, consentAccepted = true,
            userEnabled = true, masterEnabled = true))
        assertFalse(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = false,
            userEnabled = true, masterEnabled = true))
        assertFalse(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = true,
            userEnabled = false, masterEnabled = true))
        assertFalse(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = true,
            userEnabled = true, masterEnabled = false))
    }

    @Test
    fun `userEnabled defaults off and masterEnabled defaults on`() {
        assertFalse(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = true))
        assertTrue(AiEntryVisibilityPolicy.shouldShowAiSurfaces(
            chatAiEnabled = true, consentAccepted = true, userEnabled = true))
    }

    @Test
    fun `composer entry follows chat flag`() {
        assertTrue(AiEntryVisibilityPolicy.isComposerEntryActive(true))
        assertFalse(AiEntryVisibilityPolicy.isComposerEntryActive(false))
    }
}
