package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomStatusPolicyTest {
    @Test
    fun `normalize trims and caps length`() {
        assertEquals("", CustomStatusPolicy.normalize("   "))
        assertEquals("hi", CustomStatusPolicy.normalize("  hi  "))
        val long = "x".repeat(100)
        assertEquals(80, CustomStatusPolicy.normalize(long).length)
    }

    @Test
    fun `isValid rejects over max`() {
        assertTrue(CustomStatusPolicy.isValid(""))
        assertTrue(CustomStatusPolicy.isValid("ok"))
        assertFalse(CustomStatusPolicy.isValid("y".repeat(81)))
    }

    @Test
    fun `visibleStatus respects privacy switch`() {
        assertEquals("", CustomStatusPolicy.visibleStatus("忙碌", showStatus = false))
        assertEquals("忙碌", CustomStatusPolicy.visibleStatus(" 忙碌 ", showStatus = true))
        assertEquals("", CustomStatusPolicy.visibleStatus("   ", showStatus = true))
    }
}
