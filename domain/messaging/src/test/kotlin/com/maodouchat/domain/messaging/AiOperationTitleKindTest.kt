package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiOperationTitleKindTest {

    @Test
    fun `all five known operations map to their kinds`() {
        assertEquals(AiOperationTitleKind.TRANSCRIBE_VOICE, aiOperationTitleKind("TRANSCRIBE_VOICE"))
        assertEquals(AiOperationTitleKind.TRANSLATE_MESSAGE, aiOperationTitleKind("TRANSLATE_MESSAGE"))
        assertEquals(AiOperationTitleKind.SUMMARIZE_MESSAGES, aiOperationTitleKind("SUMMARIZE_MESSAGES"))
        assertEquals(AiOperationTitleKind.ANALYZE_IMAGE, aiOperationTitleKind("ANALYZE_IMAGE"))
        assertEquals(AiOperationTitleKind.ANALYZE_FILE, aiOperationTitleKind("ANALYZE_FILE"))
    }

    @Test
    fun `unknown and empty operations fall back to generic`() {
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind("SUMMARIZE_CHAT"))
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind(""))
    }

    @Test
    fun `matching is case sensitive like the original when`() {
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind("transcribe_voice"))
    }
}
