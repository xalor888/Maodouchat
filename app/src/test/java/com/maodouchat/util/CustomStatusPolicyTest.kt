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

    @Test
    fun `normalize trims tabs and newlines and keeps inner whitespace`() {
        assertEquals("hi", CustomStatusPolicy.normalize("\t hi \n"))
        assertEquals("", CustomStatusPolicy.normalize("\t\n "))
        assertEquals("a\tb", CustomStatusPolicy.normalize("  a\tb  "))
    }

    @Test
    fun `isValid accepts exactly 80 chars with single-side padding`() {
        assertTrue(CustomStatusPolicy.isValid(" " + "x".repeat(79)))
        assertEquals(79, CustomStatusPolicy.normalize(" " + "x".repeat(79)).length)
        assertTrue(CustomStatusPolicy.isValid("x".repeat(79) + " "))
        assertEquals(79, CustomStatusPolicy.normalize("x".repeat(79) + " ").length)
    }

    @Test
    fun `visibleStatus strips tabs and newlines when shown and stays empty when hidden`() {
        assertEquals("忙碌", CustomStatusPolicy.visibleStatus("\n忙碌\t", showStatus = true))
        assertEquals("", CustomStatusPolicy.visibleStatus("x".repeat(100), showStatus = false))
        assertEquals("", CustomStatusPolicy.visibleStatus("  \t ", showStatus = false))
    }

    @Test
    fun `presets survive normalize unchanged and are all valid`() {
        assertEquals(CustomStatusPolicy.PRESETS, CustomStatusPolicy.PRESETS.map { CustomStatusPolicy.normalize(it) })
        assertTrue(CustomStatusPolicy.PRESETS.all { CustomStatusPolicy.isValid(it) })
    }

    @Test
    fun `normalize is idempotent and its output is always valid`() {
        listOf(null, "", "  hi  ", "a".repeat(100), "  " + "b".repeat(79)).forEach { raw ->
            val once = CustomStatusPolicy.normalize(raw)
            assertEquals(once, CustomStatusPolicy.normalize(once))
            assertTrue(CustomStatusPolicy.isValid(once))
        }
    }

    @Test
    fun `isValid counts multi-byte chars not bytes at boundary`() {
        assertTrue(CustomStatusPolicy.isValid("忙".repeat(80)))
        assertFalse(CustomStatusPolicy.isValid("忙".repeat(81)))
    }

    @Test
    fun `presets first five in fixed order`() {
        assertEquals(
            listOf("在线", "忙碌", "开会中", "请勿打扰", "马上回来"),
            CustomStatusPolicy.PRESETS.take(5),
        )
    }

    @Test
    fun `normalize keeps emoji and trims around it`() {
        assertEquals("😀 hi", CustomStatusPolicy.normalize("  😀 hi  "))
    }

    @Test
    fun `visibleStatus shows multi-byte status and caps it at 80 chars`() {
        assertEquals("忙".repeat(80), CustomStatusPolicy.visibleStatus("忙".repeat(100), showStatus = true))
        assertEquals("", CustomStatusPolicy.visibleStatus("忙碌", showStatus = false))
    }

    @Test
    fun `visibleStatus trims then caps padded long status`() {
        assertEquals(
            "y".repeat(80),
            CustomStatusPolicy.visibleStatus("  " + "y".repeat(100) + "  ", showStatus = true),
        )
    }

}
