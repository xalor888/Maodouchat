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

    @Test
    fun `normalize pins MAX_TRANSCRIPT_LENGTH and treats null as empty`() {
        assertEquals("", VoiceTranscriptPolicy.normalize(null))
        assertFalse(VoiceTranscriptPolicy.hasTranscript(null))
        assertEquals(
            VoiceTranscriptPolicy.MAX_TRANSCRIPT_LENGTH,
            VoiceTranscriptPolicy.normalize("x".repeat(VoiceTranscriptPolicy.MAX_TRANSCRIPT_LENGTH + 1)).length
        )
    }

    @Test
    fun `expand toggle boundary at PREVIEW_MAX_CHARS`() {
        val chars = VoiceTranscriptPolicy.PREVIEW_MAX_CHARS
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle("a".repeat(chars)))
        assertTrue(VoiceTranscriptPolicy.needsExpandToggle("a".repeat(chars + 1)))
    }

    @Test
    fun `displayText keeps full text exactly at the boundary`() {
        val chars = VoiceTranscriptPolicy.PREVIEW_MAX_CHARS
        val full = "a".repeat(chars)
        assertEquals(full, VoiceTranscriptPolicy.displayText(full, expanded = false))
        val over = VoiceTranscriptPolicy.displayText("a".repeat(chars + 1), expanded = false)
        assertTrue(over.endsWith("…"))
        assertEquals(chars + 1, over.length)
    }

    @Test
    fun `displayText trims trailing spaces before the ellipsis`() {
        // take(360) 恰好切在空格上时，省略号直接贴着正文、不留空格
        val transcript = "a".repeat(359) + " " + "b".repeat(100)
        assertEquals("a".repeat(359) + "…", VoiceTranscriptPolicy.displayText(transcript, expanded = false))
        // 输入尾部空格先被 normalize 裁掉：355 有效字符 ≤360，不截断也不加省略号
        assertEquals("a".repeat(355), VoiceTranscriptPolicy.displayText("a".repeat(355) + " ".repeat(10), expanded = false))
    }

    @Test
    fun `whitespace-only transcript still shows the inline entry`() {
        assertTrue(VoiceTranscriptPolicy.shouldShowInlineEntry(true, "   ", false))
        assertTrue(VoiceTranscriptPolicy.canRequest(true, "   ", false))
        assertFalse(VoiceTranscriptPolicy.canRequest(true, "  ok  ", false))
    }

    @Test
    fun `normalize keeps inner whitespace and newlines`() {
        assertEquals("a  b\tc", VoiceTranscriptPolicy.normalize("a  b\tc"))
        assertEquals("a\nb", VoiceTranscriptPolicy.normalize("a\nb"))
        assertEquals("hello", VoiceTranscriptPolicy.normalize("\t\n hello \n"))
    }

    @Test
    fun `hasTranscript treats padded and tab-only inputs correctly`() {
        assertTrue(VoiceTranscriptPolicy.hasTranscript(" x "))
        assertFalse(VoiceTranscriptPolicy.hasTranscript("\t\n"))
        assertFalse(VoiceTranscriptPolicy.hasTranscript(""))
    }

    @Test
    fun `inline entry and canRequest agree on every combination`() {
        val transcripts: List<String?> = listOf(null, "   ", "done")
        for (isVoice in listOf(true, false)) {
            for (transcript in transcripts) {
                for (isTranscribing in listOf(true, false)) {
                    assertEquals(
                        VoiceTranscriptPolicy.canRequest(isVoice, transcript, isTranscribing),
                        VoiceTranscriptPolicy.shouldShowInlineEntry(isVoice, transcript, isTranscribing),
                    )
                }
            }
        }
    }

    @Test
    fun `displayText of null or blank is empty in both states`() {
        assertEquals("", VoiceTranscriptPolicy.displayText(null, false))
        assertEquals("", VoiceTranscriptPolicy.displayText(null, true))
        assertEquals("", VoiceTranscriptPolicy.displayText("   ", false))
        assertEquals("", VoiceTranscriptPolicy.displayText("   ", true))
    }

    @Test
    fun `overlong transcript is capped then previewed`() {
        val capped = VoiceTranscriptPolicy.normalize("x".repeat(7000))
        assertEquals(VoiceTranscriptPolicy.MAX_TRANSCRIPT_LENGTH, capped.length)
        val preview = VoiceTranscriptPolicy.displayText("x".repeat(7000), expanded = false)
        assertEquals(VoiceTranscriptPolicy.PREVIEW_MAX_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals(capped, VoiceTranscriptPolicy.displayText("x".repeat(7000), expanded = true))
    }
}
