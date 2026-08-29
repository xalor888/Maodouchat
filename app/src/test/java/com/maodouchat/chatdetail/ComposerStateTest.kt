package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.ComposerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStateTest {
    @Test
    fun `primary panels are mutually exclusive`() {
        val state = ComposerState()

        state.toggleAttachMenu()
        assertTrue(state.snapshot().attachMenuVisible)

        state.toggleExpressionPanel()
        val expression = state.snapshot()
        assertFalse(expression.attachMenuVisible)
        assertTrue(expression.expressionPanelVisible)

        state.showAiMenu()
        val ai = state.snapshot()
        assertFalse(ai.expressionPanelVisible)
        assertTrue(ai.aiMenuVisible)
    }

    @Test
    fun `back dismissal follows visible panel priority`() {
        val state = ComposerState()
        state.openQuickPhrases()

        assertTrue(state.dismissTopPanel())
        assertFalse(state.snapshot().quickPhrasesVisible)
        assertFalse(state.dismissTopPanel())
    }

    @Test
    fun `expression mode remains in snapshot across panel transitions`() {
        val state = ComposerState()
        state.expressionMode.value = "STICKER"
        state.toggleExpressionPanel()
        state.toggleAttachMenu()

        assertEquals("STICKER", state.snapshot().expressionMode)
        assertTrue(state.snapshot().attachMenuVisible)
        assertFalse(state.snapshot().expressionPanelVisible)
    }
}
