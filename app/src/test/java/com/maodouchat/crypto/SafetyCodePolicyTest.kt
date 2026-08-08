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
        assertTrue(SafetyCodePolicy.isStickyIdentityWarning(SignalProtocol.IdentityTrustState.CHANGED))
        assertFalse(SafetyCodePolicy.isStickyIdentityWarning(SignalProtocol.IdentityTrustState.TRUSTED))
        assertFalse(SafetyCodePolicy.isStickyIdentityWarning(SignalProtocol.IdentityTrustState.VERIFIED))
    }
}
