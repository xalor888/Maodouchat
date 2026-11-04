package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindUrlRangesTest {

    @Test
    fun `single http url is found`() {
        assertEquals(listOf(4 to 16), findUrlRanges("see http://a.com now"))
    }

    @Test
    fun `trailing punctuation is stripped`() {
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com."))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com!!!"))
    }

    @Test
    fun `no url returns empty`() {
        assertTrue(findUrlRanges("plain text").isEmpty())
        assertTrue(findUrlRanges("").isEmpty())
    }
}
