package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEntryPolicyTest {
    @Test
    fun `composer section order is draft chat settings`() {
        assertEquals(
            listOf(
                AiEntryPolicy.ComposerSection.DRAFT,
                AiEntryPolicy.ComposerSection.CHAT,
                AiEntryPolicy.ComposerSection.SETTINGS
            ),
            AiEntryPolicy.COMPOSER_SECTION_ORDER
        )
        assertEquals("composer_menu", AiEntryPolicy.PRIMARY_SURFACE)
        assertEquals("message_actions", AiEntryPolicy.CONTEXT_SURFACE)
        assertEquals("ai_privacy", AiEntryPolicy.SETTINGS_SURFACE)
    }

    @Test
    fun `context actions by type`() {
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("TEXT")
        )
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("VOICE", hasTranscript = false)
        )
        assertTrue(AiEntryPolicy.contextActionsFor("VOICE", hasTranscript = true).isEmpty())
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("IMAGE")
        )
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("FILE")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("STICKER").isEmpty())
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor(" system ")
        )
        assertEquals(
            listOf(AiEntryPolicy.MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("text")
        )
    }

    @Test
    fun `composer and run gates`() {
        assertTrue(AiEntryPolicy.isComposerEntryActive(true))
        assertFalse(AiEntryPolicy.isComposerEntryActive(false))
        assertTrue(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = false))
        assertFalse(AiEntryPolicy.canRunContextAction(chatAiEnabled = false, isBusy = false))
        assertFalse(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = true))
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = false,
                chatAiEnabled = true,
                isBusy = false
            )
        )
        assertTrue(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = false))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = true))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
    }

    @Test
    fun `ai surfaces stay hidden until settings are on`() {
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = false
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = false,
                userEnabled = true
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = false,
                consentAccepted = true,
                userEnabled = true
            )
        )
        assertTrue(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = true
            )
        )
    }
}
