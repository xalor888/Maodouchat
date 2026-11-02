package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomStatusPolicyTest {

    @Test
    fun `normalize trims and truncates to max length`() {
        assertEquals("在线", CustomStatusPolicy.normalize("  在线  "))
        assertEquals("", CustomStatusPolicy.normalize("   "))
        assertEquals(80, CustomStatusPolicy.normalize("a".repeat(100)).length)
    }

    @Test
    fun `isValid rejects overlong status`() {
        assertTrue(CustomStatusPolicy.isValid("在线"))
        assertFalse(CustomStatusPolicy.isValid("a".repeat(81)))
    }

    @Test
    fun `visibleStatus hides when privacy is off`() {
        assertEquals("", CustomStatusPolicy.visibleStatus("在线", false))
        assertEquals("在线", CustomStatusPolicy.visibleStatus("  在线 ", true))
    }

    @Test
    fun `presets are non-blank and within limits`() {
        assertEquals(24, CustomStatusPolicy.PRESETS.size)
        CustomStatusPolicy.PRESETS.forEach { preset ->
            assertTrue(preset.isNotBlank())
            assertTrue(preset.length <= CustomStatusPolicy.MAX_LENGTH)
        }
    }
}
