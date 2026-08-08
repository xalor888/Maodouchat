package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWritingStylePolicyTest {

    @Test
    fun `default clear has no rewrite hint`() {
        val snap = AiWritingStylePolicy.clear()
        assertFalse(snap.enabled)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
        assertFalse(snap.hasMemorableContent)
    }

    @Test
    fun `disabled normalize drops custom content`() {
        val snap = AiWritingStylePolicy.normalize(
            enabled = false,
            presetId = "formal",
            customNote = "  keep short  "
        )
        assertFalse(snap.enabled)
        assertEquals(AiWritingStylePolicy.Preset.NONE, snap.preset)
        assertEquals("", snap.customNote)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
    }

    @Test
    fun `enabled preset and custom produce hint and cap length`() {
        val longNote = "x".repeat(500)
        val snap = AiWritingStylePolicy.normalize(true, "warm", longNote)
        assertTrue(snap.enabled)
        assertEquals(AiWritingStylePolicy.Preset.WARM, snap.preset)
        assertEquals(AiWritingStylePolicy.MAX_CUSTOM_CHARS, snap.customNote.length)
        val hint = AiWritingStylePolicy.rewriteStyleHint(snap)
        assertTrue(hint!!.contains("warm", ignoreCase = true) || hint.contains("friendly"))
        assertTrue(hint.contains("User style note"))
        assertTrue(snap.hasMemorableContent)
    }

    @Test
    fun `unknown preset falls back to none`() {
        val snap = AiWritingStylePolicy.normalize(true, "nope", "  ")
        assertEquals(AiWritingStylePolicy.Preset.NONE, snap.preset)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
        assertFalse(snap.hasMemorableContent)
    }
}
