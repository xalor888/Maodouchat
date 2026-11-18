package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.MessageAiAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageAiActionPolicyTest {

    @Test
    fun `text markdown and system map to translate`() {
        assertEquals(listOf(MessageAiAction.TRANSLATE),
            MessageAiActionPolicy.contextActionsFor("TEXT"))
        assertEquals(listOf(MessageAiAction.TRANSLATE),
            MessageAiActionPolicy.contextActionsFor("markdown"))
        assertEquals(listOf(MessageAiAction.TRANSLATE),
            MessageAiActionPolicy.contextActionsFor("  system  "))
    }

    @Test
    fun `voice without transcript maps to transcribe, with transcript maps to none`() {
        assertEquals(listOf(MessageAiAction.TRANSCRIBE),
            MessageAiActionPolicy.contextActionsFor("VOICE"))
        assertEquals(emptyList<MessageAiAction>(),
            MessageAiActionPolicy.contextActionsFor("VOICE", hasTranscript = true))
    }

    @Test
    fun `image gif and file map to their analyze actions`() {
        assertEquals(listOf(MessageAiAction.ANALYZE_IMAGE),
            MessageAiActionPolicy.contextActionsFor("IMAGE"))
        assertEquals(listOf(MessageAiAction.ANALYZE_IMAGE),
            MessageAiActionPolicy.contextActionsFor("gif"))
        assertEquals(listOf(MessageAiAction.ANALYZE_FILE),
            MessageAiActionPolicy.contextActionsFor("FILE"))
    }

    @Test
    fun `null and unknown types map to no actions`() {
        assertEquals(emptyList<MessageAiAction>(),
            MessageAiActionPolicy.contextActionsFor(null))
        assertEquals(emptyList<MessageAiAction>(),
            MessageAiActionPolicy.contextActionsFor("VIDEO"))
        assertFalse(MessageAiActionPolicy.hasContextAiActions("VIDEO"))
        assertTrue(MessageAiActionPolicy.hasContextAiActions("TEXT"))
    }

    @Test
    fun `composer menu opens only when idle`() {
        assertTrue(MessageAiActionPolicy.canOpenComposerMenu(isBusy = false))
        assertFalse(MessageAiActionPolicy.canOpenComposerMenu(isBusy = true))
        assertFalse(MessageAiActionPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
    }

    @Test
    fun `context action runs only when master chat enabled and idle`() {
        assertTrue(MessageAiActionPolicy.canRunContextAction(
            masterEnabled = true, chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(
            masterEnabled = false, chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(
            masterEnabled = true, chatAiEnabled = false, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(
            masterEnabled = true, chatAiEnabled = true, isBusy = true))
        assertTrue(MessageAiActionPolicy.canRunContextAction(chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(chatAiEnabled = true, isBusy = true))
    }
}
