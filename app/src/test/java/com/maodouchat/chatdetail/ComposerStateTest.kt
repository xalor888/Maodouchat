package com.maodouchat.chatdetail

import androidx.compose.runtime.saveable.SaverScope
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

    @Test
    fun `contact card picker survives transient panel switches`() {
        val state = ComposerState()
        state.openContactCardPicker()
        state.toggleAttachMenu()

        // 非瞬时面板不受互斥逻辑影响
        val snapshot = state.snapshot()
        assertTrue(snapshot.contactCardPickerVisible)
        assertTrue(snapshot.attachMenuVisible)
    }

    @Test
    fun `dismiss top panel closes in ai quickPhrases attach expression order`() {
        val state = ComposerState()
        state.aiMenu.value = true
        state.quickPhrases.value = true

        assertTrue(state.dismissTopPanel())
        assertFalse(state.snapshot().aiMenuVisible)
        assertTrue(state.snapshot().quickPhrasesVisible)
        assertTrue(state.dismissTopPanel())
        assertFalse(state.snapshot().quickPhrasesVisible)
    }

    @Test
    fun `toggling the same panel twice returns to rest`() {
        val state = ComposerState()
        state.toggleExpressionPanel()
        state.toggleExpressionPanel()

        assertEquals(ComposerState().snapshot(), state.snapshot())
    }

    @Test
    fun `saver round trip preserves panels and expression mode`() {
        val state = ComposerState()
        state.expressionMode.value = "STICKER"
        state.openQuickPhrases()
        state.openContactCardPicker()

        // save 是 Saver 的成员扩展函数：dispatch receiver 必须是 Saver 实例
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ComposerState.Saver) { scope.save(state) }
        val restored = ComposerState.Saver.restore(saved!!)

        assertEquals(state.snapshot(), restored?.snapshot())
    }

    @Test
    fun `dismiss top panel ignores the contact card picker`() {
        val state = ComposerState()
        state.openContactCardPicker()

        assertFalse(state.dismissTopPanel())
        assertTrue(state.snapshot().contactCardPickerVisible)
    }

    @Test
    fun `dismiss transient panels clears the four primary panels`() {
        val state = ComposerState()
        state.attachMenu.value = true
        state.expressionPanel.value = true
        state.aiMenu.value = true
        state.quickPhrases.value = true

        state.dismissTransientPanels()
        val snapshot = state.snapshot()

        assertFalse(snapshot.attachMenuVisible)
        assertFalse(snapshot.expressionPanelVisible)
        assertFalse(snapshot.aiMenuVisible)
        assertFalse(snapshot.quickPhrasesVisible)
    }

    @Test
    fun `translation languages survive transient panel switches`() {
        val state = ComposerState()
        state.translationLanguages.value = true

        state.toggleAttachMenu()
        assertTrue(state.snapshot().translationLanguagesVisible)

        state.dismissTransientPanels()
        assertTrue(state.snapshot().translationLanguagesVisible)
    }

    @Test
    fun `contact card picker survives dismiss transient panels`() {
        val state = ComposerState()
        state.openContactCardPicker()

        state.dismissTransientPanels()
        val snapshot = state.snapshot()

        assertTrue(snapshot.contactCardPickerVisible)
        assertFalse(snapshot.attachMenuVisible)
    }

    @Test
    fun `fresh state defaults expression mode to emoji`() {
        assertEquals(ComposerState.EXPRESSION_MODE_EMOJI, ComposerState().snapshot().expressionMode)
    }

    @Test
    fun `opening quick phrases dismisses the ai menu`() {
        val state = ComposerState()
        state.showAiMenu()
        assertTrue(state.snapshot().aiMenuVisible)

        state.openQuickPhrases()
        val snapshot = state.snapshot()

        assertFalse(snapshot.aiMenuVisible)
        assertTrue(snapshot.quickPhrasesVisible)
    }
}
