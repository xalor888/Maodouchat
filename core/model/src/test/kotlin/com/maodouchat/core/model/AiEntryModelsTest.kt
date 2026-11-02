package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEntryModelsTest {

    @Test
    fun `composer sections keep their order`() {
        assertEquals(
            listOf(ComposerSection.DRAFT, ComposerSection.CHAT, ComposerSection.SETTINGS),
            ComposerSection.entries
        )
    }

    @Test
    fun `context actions cover the four message kinds`() {
        assertEquals(
            setOf(
                MessageAiAction.TRANSLATE,
                MessageAiAction.TRANSCRIBE,
                MessageAiAction.ANALYZE_IMAGE,
                MessageAiAction.ANALYZE_FILE
            ),
            MessageAiAction.entries.toSet()
        )
    }

    @Test
    fun `sanitized line keeps its fields and copies`() {
        val line = SanitizedContextLine(sender = "user", text = "hello")
        assertEquals("user", line.sender)
        assertEquals("hello", line.text)
        assertEquals(line, line.copy(text = "hello"))
    }
}
