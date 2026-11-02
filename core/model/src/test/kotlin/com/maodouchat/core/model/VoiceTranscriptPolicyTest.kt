package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptPolicyTest {

    @Test
    fun `normalize trims and caps at 6000 chars`() {
        assertEquals("hi", VoiceTranscriptPolicy.normalize("  hi  "))
        assertEquals(6_000, VoiceTranscriptPolicy.normalize("x".repeat(7_000)).length)
        assertFalse(VoiceTranscriptPolicy.hasTranscript("   "))
        assertTrue(VoiceTranscriptPolicy.hasTranscript("ok"))
    }

    @Test
    fun `inline entry shows only for voice without transcript and not transcribing`() {
        assertTrue(VoiceTranscriptPolicy.shouldShowInlineEntry(true, null, false))
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(false, null, false))
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(true, "done", false))
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(true, null, true))
        assertTrue(VoiceTranscriptPolicy.canRequest(true, null, false))
    }

    @Test
    fun `long transcripts collapse with ellipsis until expanded`() {
        val long = "y".repeat(400)
        assertTrue(VoiceTranscriptPolicy.needsExpandToggle(long))
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle("short"))
        val collapsed = VoiceTranscriptPolicy.displayText(long, expanded = false)
        assertTrue(collapsed.endsWith("…"))
        assertEquals(360 + 1, collapsed.length)
        assertEquals(long, VoiceTranscriptPolicy.displayText(long, expanded = true))
    }
}
