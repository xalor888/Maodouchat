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
        assertEquals(Preset.NONE, snap.preset)
        assertEquals("", snap.customNote)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
    }

    @Test
    fun `enabled preset and custom produce hint and cap length`() {
        val longNote = "x".repeat(500)
        val snap = AiWritingStylePolicy.normalize(true, "warm", longNote)
        assertTrue(snap.enabled)
        assertEquals(Preset.WARM, snap.preset)
        assertEquals(AiWritingStylePolicy.MAX_CUSTOM_CHARS, snap.customNote.length)
        val hint = AiWritingStylePolicy.rewriteStyleHint(snap)
        assertTrue(hint!!.contains("warm", ignoreCase = true) || hint.contains("friendly"))
        assertTrue(hint.contains("User style note"))
        assertTrue(snap.hasMemorableContent)
    }

    @Test
    fun `unknown preset falls back to none`() {
        val snap = AiWritingStylePolicy.normalize(true, "nope", "  ")
        assertEquals(Preset.NONE, snap.preset)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
        assertFalse(snap.hasMemorableContent)
    }

    @Test
    fun `normalizeCustomNote trims and collapses whitespace`() {
        assertEquals("hello world", AiWritingStylePolicy.normalizeCustomNote("  hello\n\nworld  "))
        assertEquals("", AiWritingStylePolicy.normalizeCustomNote(null))
        assertEquals("", AiWritingStylePolicy.normalizeCustomNote("   "))
    }

    @Test
    fun `preset id is case and whitespace insensitive`() {
        assertEquals(
            Preset.WARM,
            AiWritingStylePolicy.normalize(true, "  WARM  ", "").preset
        )
        // over-long ids are cut before lookup, so they never match a preset.
        assertEquals(
            Preset.NONE,
            AiWritingStylePolicy.normalize(true, "x".repeat(100), "").preset
        )
    }

    @Test
    fun `hint with preset only carries preset wording`() {
        val snap = AiWritingStylePolicy.normalize(true, "concise", null)
        val hint = AiWritingStylePolicy.rewriteStyleHint(snap)
        assertEquals("Prefer concise wording.", hint)
        assertFalse(hint!!.contains("User style note"))
        assertTrue(snap.hasMemorableContent)
    }

    @Test
    fun `hint with custom note only carries the note`() {
        val snap = AiWritingStylePolicy.normalize(true, "none", "  short  ")
        assertEquals("short", snap.customNote)
        val hint = AiWritingStylePolicy.rewriteStyleHint(snap)
        assertTrue(hint!!.startsWith("User style note"))
        assertTrue(snap.hasMemorableContent)
    }

    @Test
    fun `enabled empty preset and blank note yields no hint`() {
        val snap = AiWritingStylePolicy.normalize(true, "none", "   ")
        assertTrue(snap.enabled)
        assertEquals("", snap.customNote)
        assertNull(AiWritingStylePolicy.rewriteStyleHint(snap))
        assertFalse(snap.hasMemorableContent)
    }
}
