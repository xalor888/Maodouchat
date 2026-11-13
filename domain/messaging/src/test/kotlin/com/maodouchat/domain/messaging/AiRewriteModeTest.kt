package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiRewriteModeTest {

    @Test
    fun allowedModesPassThrough() {
        listOf(
            "polish", "shorten", "formal", "gentle", "casual",
            "professional", "expand", "bullet", "translate", "clarify",
        ).forEach { assertEquals(it, normalizeAiRewriteMode(it)) }
    }

    @Test
    fun caseAndWhitespaceNormalized() {
        assertEquals("formal", normalizeAiRewriteMode(" Formal "))
        assertEquals("polish", normalizeAiRewriteMode("POLISH"))
    }

    @Test
    fun nullFallsBackToPolish() {
        assertEquals("polish", normalizeAiRewriteMode(null))
    }

    @Test
    fun blankFallsBackToPolish() {
        assertEquals("polish", normalizeAiRewriteMode("   "))
        assertEquals("polish", normalizeAiRewriteMode(""))
    }

    @Test
    fun unknownFallsBackToPolish() {
        assertEquals("polish", normalizeAiRewriteMode("rewrite"))
        assertEquals("polish", normalizeAiRewriteMode("polish2"))
    }
}
