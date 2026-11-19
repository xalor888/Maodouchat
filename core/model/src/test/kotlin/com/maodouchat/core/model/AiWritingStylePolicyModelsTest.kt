package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiWritingStylePolicyModelsTest {

    @Test
    fun `preset ids cover all eleven values`() {
        assertEquals(
            listOf(
                "none", "concise", "formal", "warm", "professional", "casual",
                "witty", "empathetic", "direct", "enthusiastic", "diplomatic"
            ),
            Preset.entries.map { it.id }
        )
    }

    @Test
    fun `preset fromId trims lowercases and falls back to none`() {
        assertEquals(Preset.WARM, Preset.fromId("  Warm "))
        assertEquals(Preset.NONE, Preset.fromId("xyz"))
        assertEquals(Preset.NONE, Preset.fromId(null))
    }
}
