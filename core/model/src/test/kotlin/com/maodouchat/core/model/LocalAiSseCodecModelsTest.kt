package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiSseCodecModelsTest {

    @Test
    fun `sse delta parts default to empty strings`() {
        val parts = SseDeltaParts()
        assertEquals("", parts.content)
        assertEquals("", parts.reasoning)
        assertEquals("chunk", parts.copy(content = "chunk").content)
    }
}
