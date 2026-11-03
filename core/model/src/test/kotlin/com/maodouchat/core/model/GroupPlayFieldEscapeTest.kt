package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupPlayFieldEscapeTest {

    @Test
    fun `esc escapes pipe and caret`() {
        assertEquals("a\u0001b\u0002c", GroupPlayFieldEscape.esc("a|b^c"))
    }

    @Test
    fun `unesc restores pipe and caret`() {
        assertEquals("a|b^c", GroupPlayFieldEscape.unesc("a\u0001b\u0002c"))
    }

    @Test
    fun `esc unesc round-trip`() {
        val raw = "a|b^c|d"
        assertEquals(raw, GroupPlayFieldEscape.unesc(GroupPlayFieldEscape.esc(raw)))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("hello", GroupPlayFieldEscape.esc("hello"))
        assertEquals("hello", GroupPlayFieldEscape.unesc("hello"))
        assertEquals("", GroupPlayFieldEscape.esc(""))
    }
}
