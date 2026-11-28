package com.maodouchat.domain.messaging

import com.maodouchat.core.model.IdentityTrustState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [SafetyCodePolicy] (checklist line 931, 34th block).
 * Test-only, zero production changes.
 *
 * Oracles re-derive the behaviour from the spec rather than re-running
 * production code: digits are ASCII 0-9 only (the regex `[^0-9]` strips
 * everything else, including non-ASCII digits), display groups are chunks
 * of 5, and a match needs a non-empty digit string on both sides. The
 * random alphabet deliberately mixes in Arabic-Indic digits and accented
 * letters, which look numeric but must be stripped. A Python mini-model
 * differentially checks all three oracles on 5000 random string pairs
 * (0 diffs).
 */
class SafetyCodePolicyFuzzTest {

    private val alphabet = listOf(
        "0", "1", "5", "9", "a", "b", "X", "Z", "-", " ", "+", "(", ")", "#",
        "\u00e9", "\u0665", "\u0666"
    )

    private fun randomCode(r: Random): String? {
        if (r.nextInt(10) == 0) return null
        return buildString {
            repeat(r.nextInt(30)) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    private fun oracleDigitsOnly(code: String?): String =
        code.orEmpty().filter { it in '0'..'9' }

    private fun oracleFormatForDisplay(code: String?): String? {
        val digits = oracleDigitsOnly(code)
        if (digits.isEmpty()) return null
        return digits.chunked(5).joinToString(" ")
    }

    private fun oracleCodesMatch(local: String?, remote: String?): Boolean {
        val a = oracleDigitsOnly(local)
        val b = oracleDigitsOnly(remote)
        return a.isNotEmpty() && a == b
    }

    @Test
    fun digitsOnlyMatchesOracle() {
        val r = Random(3409)
        repeat(300) {
            val code = randomCode(r)
            assertEquals(oracleDigitsOnly(code), SafetyCodePolicy.digitsOnly(code))
        }
    }

    @Test
    fun formatForDisplayMatchesOracle() {
        val r = Random(3410)
        repeat(300) {
            val code = randomCode(r)
            assertEquals(oracleFormatForDisplay(code), SafetyCodePolicy.formatForDisplay(code))
            assertEquals(oracleFormatForDisplay(code), SafetyCodePolicy.formatForCopy(code))
        }
    }

    @Test
    fun codesMatchMatchesOracle() {
        val r = Random(3411)
        repeat(300) {
            val local = randomCode(r)
            val remote = randomCode(r)
            assertEquals(oracleCodesMatch(local, remote), SafetyCodePolicy.codesMatch(local, remote))
        }
        // Same digits under different separators must match; different
        // digits must not; empty on either side must not.
        assertTrue(SafetyCodePolicy.codesMatch("01234-56789", "01234 56789"))
        assertTrue(SafetyCodePolicy.codesMatch("+0123456789", "0123456789"))
        assertFalse(SafetyCodePolicy.codesMatch("01234-56789", "01234-56780"))
        assertFalse(SafetyCodePolicy.codesMatch("", "01234"))
        assertFalse(SafetyCodePolicy.codesMatch(null, "01234"))
        assertFalse(SafetyCodePolicy.codesMatch("abc", "def"))
    }

    @Test
    fun formatGroupsAndEmptyCasesArePinned() {
        assertEquals("01234 05678 90123", SafetyCodePolicy.formatForDisplay("012340567890123"))
        assertEquals("01234 05678 9012", SafetyCodePolicy.formatForDisplay("01234-05678-9012"))
        assertEquals("0", SafetyCodePolicy.formatForDisplay("a0b"))
        assertNull(SafetyCodePolicy.formatForDisplay(""))
        assertNull(SafetyCodePolicy.formatForDisplay(null))
        assertNull(SafetyCodePolicy.formatForDisplay("abc- def"))
        // Non-ASCII digits are stripped, not kept.
        assertNull(SafetyCodePolicy.formatForDisplay("\u0665\u0666"))
        assertEquals("5", SafetyCodePolicy.formatForDisplay("x5\u0665"))
    }

    @Test
    fun onlyChangedIsSticky() {
        for (state in IdentityTrustState.entries) {
            assertEquals(
                state == IdentityTrustState.CHANGED,
                SafetyCodePolicy.isStickyIdentityWarning(state)
            )
        }
    }
}
