package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptPolicyTest {
    @Test
    fun `normalize and hasTranscript`() {
        assertEquals("hello", VoiceTranscriptPolicy.normalize("  hello  "))
        assertFalse(VoiceTranscriptPolicy.hasTranscript("   "))
        assertTrue(VoiceTranscriptPolicy.hasTranscript("ok"))
        assertEquals(
            3_000,
            VoiceTranscriptPolicy.normalize("x".repeat(3_000)).length
        )
    }

    @Test
    fun `inline entry visibility`() {
        assertTrue(
            VoiceTranscriptPolicy.shouldShowInlineEntry(
                isVoiceMessage = true,
                transcript = null,
                isTranscribing = false
            )
        )
        assertFalse(
            VoiceTranscriptPolicy.shouldShowInlineEntry(
                isVoiceMessage = true,
                transcript = "done",
                isTranscribing = false
            )
        )
        assertFalse(
            VoiceTranscriptPolicy.shouldShowInlineEntry(
                isVoiceMessage = true,
                transcript = null,
                isTranscribing = true
            )
        )
        assertFalse(
            VoiceTranscriptPolicy.shouldShowInlineEntry(
                isVoiceMessage = false,
                transcript = null,
                isTranscribing = false
            )
        )
    }

    @Test
    fun `expand toggle and preview`() {
        val short = "short"
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle(short))
        assertEquals(short, VoiceTranscriptPolicy.displayText(short, expanded = false))

        val long = "a".repeat(VoiceTranscriptPolicy.PREVIEW_MAX_CHARS + 20)
        assertTrue(VoiceTranscriptPolicy.needsExpandToggle(long))
        val preview = VoiceTranscriptPolicy.displayText(long, expanded = false)
        assertTrue(preview.endsWith("…"))
        assertEquals(VoiceTranscriptPolicy.PREVIEW_MAX_CHARS + 1, preview.length)
        assertEquals(long, VoiceTranscriptPolicy.displayText(long, expanded = true))
    }

    @Test
    fun `canRequest guards`() {
        assertTrue(VoiceTranscriptPolicy.canRequest(true, null, false))
        assertFalse(VoiceTranscriptPolicy.canRequest(true, "x", false))
        assertFalse(VoiceTranscriptPolicy.canRequest(true, null, true))
        assertFalse(VoiceTranscriptPolicy.canRequest(false, null, false))
    }
}
