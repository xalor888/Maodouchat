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
}
