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

    @Test
    fun `presets are distinct non-blank wire values`() {
        val presets = CustomStatusPolicy.PRESETS
        assertEquals(24, presets.size)
        assertEquals(presets.size, presets.distinct().size)
        assertTrue(presets.all { it.isNotBlank() && it.length <= CustomStatusPolicy.MAX_LENGTH })
    }

    @Test
    fun `normalize handles null`() {
        assertEquals("", CustomStatusPolicy.normalize(null))
    }

    @Test
    fun `isValid boundary at exactly max`() {
        assertTrue(CustomStatusPolicy.isValid("z".repeat(80)))
        assertFalse(CustomStatusPolicy.isValid("z".repeat(81)))
        assertFalse(CustomStatusPolicy.isValid(" ".repeat(81)))
    }

    @Test
    fun `visibleStatus null status`() {
        assertEquals("", CustomStatusPolicy.visibleStatus(null, showStatus = true))
        assertEquals("", CustomStatusPolicy.visibleStatus(null, showStatus = false))
    }

    @Test
    fun `normalize caps exactly at max`() {
        val exact = "a".repeat(80)
        assertEquals(exact, CustomStatusPolicy.normalize(exact))
        assertEquals(80, CustomStatusPolicy.normalize("a".repeat(81)).length)
    }

    @Test
    fun `isValid counts raw length before trimming`() {
        val padded = "  " + "x".repeat(79)
        assertFalse(CustomStatusPolicy.isValid(padded))
        assertEquals(79, CustomStatusPolicy.normalize(padded).length)
        assertFalse(CustomStatusPolicy.isValid("x".repeat(80) + " "))
        assertEquals(80, CustomStatusPolicy.normalize("x".repeat(80) + " ").length)
    }

    @Test
    fun `isValid accepts null as empty`() {
        assertTrue(CustomStatusPolicy.isValid(null))
    }

    @Test
    fun `normalize keeps inner spacing`() {
        assertEquals("a  b", CustomStatusPolicy.normalize("  a  b  "))
    }

    @Test
    fun `visibleStatus caps long status at max when shown`() {
        assertEquals("a".repeat(80), CustomStatusPolicy.visibleStatus("a".repeat(100), showStatus = true))
    }

    @Test
    fun `presets start online and include do-not-disturb`() {
        assertEquals("在线", CustomStatusPolicy.PRESETS.first())
        assertEquals("做饭中", CustomStatusPolicy.PRESETS.last())
        assertTrue(CustomStatusPolicy.PRESETS.contains("请勿打扰"))
    }
}
