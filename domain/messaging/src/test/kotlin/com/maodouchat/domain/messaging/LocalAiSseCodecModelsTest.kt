package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.Test

class LocalAiSseCodecModelsTest {

    @Test
    fun `sse delta parts default to empty strings`() {
        val parts = SseDeltaParts()
        assertEquals("", parts.content)
        assertEquals("", parts.reasoning)
        assertEquals("chunk", parts.copy(content = "chunk").content)
    }
}
