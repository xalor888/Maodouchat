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

        // save 是 SaverScope 的扩展函数，测试里给一个恒可存的 scope
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(scope) { ComposerState.Saver.save(state) }
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
}
