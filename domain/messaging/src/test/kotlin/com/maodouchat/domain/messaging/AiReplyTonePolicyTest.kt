package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiReplyTonePolicyTest {

    @Test
    fun `all nine valid tones pass through unchanged`() {
        for (tone in listOf("natural", "friendly", "formal", "concise", "warm",
            "humorous", "direct", "empathetic", "encouraging")) {
            assertEquals(tone, AiReplyTonePolicy.normalizeTone(tone))
        }
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals("humorous", AiReplyTonePolicy.normalizeTone("HUMOROUS"))
        assertEquals("empathetic", AiReplyTonePolicy.normalizeTone("Empathetic"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("warm", AiReplyTonePolicy.normalizeTone("  warm  "))
    }

    @Test
    fun `unknown tone falls back to friendly`() {
        assertEquals("friendly", AiReplyTonePolicy.normalizeTone("sarcastic"))
    }

    @Test
    fun `blank tone falls back to friendly`() {
        assertEquals("friendly", AiReplyTonePolicy.normalizeTone("   "))
        assertEquals("friendly", AiReplyTonePolicy.normalizeTone(""))
    }
}
