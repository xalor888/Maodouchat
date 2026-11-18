package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageAiActionTest {

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
}
