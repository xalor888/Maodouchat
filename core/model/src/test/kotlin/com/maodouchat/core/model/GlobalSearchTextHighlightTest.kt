package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTextHighlightTest {

    @Test
    fun `finds case-insensitive span`() {
        assertEquals(
            listOf(HighlightSpan(6, 16)),
            GlobalSearchTextHighlight.findHighlightSpans("Hello MaodouChat world", "maodouchat")
        )
    }

    @Test
    fun `snippet keeps relative span of first match`() {
        val snippet = GlobalSearchTextHighlight.buildSnippet(
            "a".repeat(80) + " keyword appears here after noise", "keyword", maxChars = 40
        )
        assertEquals(1, snippet.highlights.size)
        val span = snippet.highlights.single()
        assertEquals("keyword", snippet.text.substring(span.start, span.end).lowercase())
        assertTrue(snippet.text.startsWith("…"))
    }
}
