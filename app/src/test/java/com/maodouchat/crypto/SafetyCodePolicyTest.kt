package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCodePolicyTest {

    @Test
    fun `format groups digits into fives`() {
        val raw = "012340567890123"
        assertEquals("01234 05678 90123", SafetyCodePolicy.formatForDisplay(raw))
        assertEquals("01234 05678 90123", SafetyCodePolicy.formatForCopy("01234-05678-90123"))
    }

    @Test
    fun `empty and blank yield null`() {
        assertNull(SafetyCodePolicy.formatForDisplay(null))
        assertNull(SafetyCodePolicy.formatForDisplay("   "))
        assertNull(SafetyCodePolicy.formatForCopy(""))
    }

    @Test
    fun `codes match ignores separators`() {
        assertTrue(SafetyCodePolicy.codesMatch("01234 05678", "0123405678"))
        assertFalse(SafetyCodePolicy.codesMatch("01234", "01235"))
        assertFalse(SafetyCodePolicy.codesMatch(null, "1"))
    }

    @Test
    fun `changed trust is sticky`() {
        assertTrue(SafetyCodePolicy.isStickyIdentityWarning(IdentityTrustState.CHANGED))
        assertFalse(SafetyCodePolicy.isStickyIdentityWarning(IdentityTrustState.TRUSTED))
        assertFalse(SafetyCodePolicy.isStickyIdentityWarning(IdentityTrustState.VERIFIED))
    }

    @Test
    fun `digitsOnly strips separators and letters`() {
        assertEquals("0123456789", SafetyCodePolicy.digitsOnly("0123-4567 ab89"))
        assertEquals("", SafetyCodePolicy.digitsOnly(null))
        assertEquals("", SafetyCodePolicy.digitsOnly("abc"))
    }

    @Test
    fun `formatForDisplay leaves a short tail group`() {
        assertEquals("01234", SafetyCodePolicy.formatForDisplay("01234"))
        assertEquals("01234 56", SafetyCodePolicy.formatForDisplay("0123456"))
    }

    @Test
    fun `codesMatch rejects empty codes`() {
        // 两边都空串也不算匹配：空码没有比较意义
        assertFalse(SafetyCodePolicy.codesMatch("", ""))
        assertFalse(SafetyCodePolicy.codesMatch("   ", ""))
        assertTrue(SafetyCodePolicy.codesMatch("0", "0"))
        assertTrue(SafetyCodePolicy.codesMatch("01234 05678", "01234-05678"))
    }

    @Test
    fun `copy form equals display form`() {
        assertEquals(
            SafetyCodePolicy.formatForDisplay("0123405678"),
            SafetyCodePolicy.formatForCopy("0123405678"),
        )
        assertNull(SafetyCodePolicy.formatForCopy(null))
    }

    @Test
    fun `only changed state is sticky`() {
        IdentityTrustState.values().forEach { state ->
            assertEquals(state == IdentityTrustState.CHANGED, SafetyCodePolicy.isStickyIdentityWarning(state))
        }
    }

    @Test
    fun `formatForDisplay normalizes separators before grouping`() {
        assertEquals("01234 56789", SafetyCodePolicy.formatForDisplay("0123-4567-89"))
        assertEquals("01234 56789", SafetyCodePolicy.formatForCopy("01234-56789"))
    }

    @Test
    fun `non-digit codes yield null`() {
        assertNull(SafetyCodePolicy.formatForDisplay(""))
        assertNull(SafetyCodePolicy.formatForDisplay("abc"))
        assertNull(SafetyCodePolicy.formatForDisplay("---"))
    }

    @Test
    fun `codesMatch ignores unicode whitespace around codes`() {
        assertTrue(SafetyCodePolicy.codesMatch(" 01234 ", "01234"))
        assertTrue(SafetyCodePolicy.codesMatch("0 1 2 3 4", "01234"))
    }

    @Test
    fun `codesMatch rejects one-sided empties`() {
        assertFalse(SafetyCodePolicy.codesMatch("", "01234"))
        assertFalse(SafetyCodePolicy.codesMatch(null, null))
    }

    @Test
    fun `long codes group in exact fives`() {
        assertEquals("00000 00000 000", SafetyCodePolicy.formatForDisplay("0".repeat(13)))
        assertEquals("01234 56789 01234 5", SafetyCodePolicy.formatForDisplay("0123456789012345"))
    }

    @Test
    fun `digitsOnly keeps ASCII digits only`() {
        // 正则只认 0-9，非 ASCII 数字整段丢弃
        assertEquals("", SafetyCodePolicy.digitsOnly("٠١٢٣٤"))
        assertEquals("12345678", SafetyCodePolicy.digitsOnly("+1 (234) x5678"))
    }

    @Test
    fun `display groups exactly ten digits`() {
        assertEquals("01234 56789", SafetyCodePolicy.formatForDisplay("0123456789"))
        assertEquals("5", SafetyCodePolicy.formatForDisplay("5"))
    }

    @Test
    fun `codesMatch rejects different lengths`() {
        assertFalse(SafetyCodePolicy.codesMatch("01234", "012345"))
        assertFalse(SafetyCodePolicy.codesMatch("01234", "0123"))
    }

    @Test
    fun `codesMatch rejects null versus empty`() {
        assertFalse(SafetyCodePolicy.codesMatch(null, ""))
    }

    @Test
    fun `copy form normalizes padded input`() {
        assertEquals("01234 56789", SafetyCodePolicy.formatForCopy("  01234 56789  "))
    }

    @Test
    fun `codesMatch ignores regrouped digits`() {
        assertTrue(SafetyCodePolicy.codesMatch("01234 56789", "0123456789"))
        assertTrue(SafetyCodePolicy.codesMatch("01234-56789", "01234 56789"))
    }

    @Test
    fun `display has no trailing separator on exact fives`() {
        // chunked(5) 整除时 join 不会多出尾空格
        assertEquals("01234 56789 01234", SafetyCodePolicy.formatForDisplay("012345678901234"))
    }

    @Test
    fun `codesMatch rejects non-ascii digits on both sides`() {
        // 正则只认 ASCII 0-9：两边都成空串也不算匹配
        assertFalse(SafetyCodePolicy.codesMatch("٠١٢٣٤", "01234"))
        assertFalse(SafetyCodePolicy.codesMatch("٠١٢٣٤", "٥٦٧٨٩"))
    }

    @Test
    fun `display groups seventeen digits with a short tail`() {
        assertEquals("01234 56789 01234 56", SafetyCodePolicy.formatForDisplay("01234567890123456"))
    }

    @Test
    fun `codesMatch treats full-width spaces as separators`() {
        // digitsOnly 剥掉一切非数字，全角空格也不例外
        assertTrue(SafetyCodePolicy.codesMatch("\u300001234\u3000", "01234"))
        assertTrue(SafetyCodePolicy.codesMatch("\u300001234\u3000", "\u300001234\u3000"))
    }
}
