package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `snapshot defaults to disabled with no memorable content`() {
        val snapshot = Snapshot()
        assertFalse(snapshot.enabled)
        assertEquals(Preset.NONE, snapshot.preset)
        assertFalse(snapshot.hasMemorableContent)
    }

    @Test
    fun `snapshot has memorable content only when enabled`() {
        assertTrue(Snapshot(enabled = true, preset = Preset.WARM).hasMemorableContent)
        assertTrue(Snapshot(enabled = true, customNote = "hi").hasMemorableContent)
        assertFalse(Snapshot(enabled = false, preset = Preset.WARM).hasMemorableContent)
    }
}
