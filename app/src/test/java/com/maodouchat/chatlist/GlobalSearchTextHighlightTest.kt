package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight
import com.maodouchat.ui.screen.chatlist.HighlightSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTextHighlightTest {
    @Test
    fun `finds case-insensitive full query span`() {
        val spans = GlobalSearchTextHighlight.findHighlightSpans("Hello MaodouChat world", "maodouchat")
        assertEquals(listOf(HighlightSpan(6, 16)), spans)
    }

    @Test
    fun `highlights multiple tokens without overlapping`() {
        val spans = GlobalSearchTextHighlight.findHighlightSpans(
            "release plan for Saturday release",
            "release plan"
        )
        assertTrue(spans.any { it.start == 0 && it.end == 12 }) // "release plan"
        // remaining "release" later
        assertTrue(spans.any { it.start == 26 && it.end == 33 })
    }

    @Test
    fun `empty query yields no spans`() {
        assertTrue(GlobalSearchTextHighlight.findHighlightSpans("anything", "  ").isEmpty())
    }

    @Test
    fun `snippet centers on first match and keeps relative spans`() {
        val longPrefix = "a".repeat(80)
        val body = "$longPrefix keyword appears here after noise"
        val snippet = GlobalSearchTextHighlight.buildSnippet(body, "keyword", maxChars = 40)
        assertTrue(snippet.text.contains("keyword"))
        assertTrue(snippet.text.startsWith("…") || snippet.text.length <= 42)
        val spans = snippet.highlights
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals("keyword", snippet.text.substring(span.start, span.end).lowercase())
    }

    @Test
    fun `short text is not truncated`() {
        val snippet = GlobalSearchTextHighlight.buildSnippet("short note", "note", maxChars = 140)
        assertEquals("short note", snippet.text)
        assertEquals(listOf(HighlightSpan(6, 10)), snippet.highlights)
    }
}
