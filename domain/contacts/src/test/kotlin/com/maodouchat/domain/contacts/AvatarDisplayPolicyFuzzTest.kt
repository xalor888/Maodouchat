package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AvatarDisplayPolicy] (checklist line 931,
 * 58th block). Test-only, zero production changes.
 *
 * The oracle re-implements String.hashCode() by hand (31-multiplier over
 * the UTF-16 units) and Int.mod as a Euclidean remainder (the result
 * takes the divisor's sign); it never calls the production object. Random
 * inputs use Random(42), fresh per @Test.
 */
class AvatarDisplayPolicyFuzzTest {

// ---------- C1 AvatarDisplayPolicy oracle ----------

// Hand-written mirror of String.hashCode(): 31-multiplier over the UTF-16
// code units, 32-bit signed wrap (Int arithmetic wraps by itself).
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// Hand-written mirror of Kotlin Int.mod (established by the differential
// drive: the result takes the divisor's sign — a truncated remainder
// shifted once by the divisor when the two signs differ). Throws
// ArithmeticException for a zero divisor, like the production % does.
private fun intModOracle(a: Int, b: Int): Int {
    val r = a % b
    return if (r == 0 || (r < 0) == (b < 0)) r else r + b
}

private fun gradientIndexOracle(name: String, gradientCount: Int): Int {
    val m = intModOracle(hashCodeOracle(name), gradientCount)
    return if (m < 0) m + gradientCount else m
}

private fun initialOracle(name: String): String =
    if (name.isEmpty()) "?" else name.substring(0, 1)

// Positive divisors only: the random @Test never throws, so it can compare
// values directly; zero / negative divisors are pinned separately.
private val GRADIENT_COUNTS = intArrayOf(1, 2, 3, 6, 12, 100, 1000, Int.MAX_VALUE)

private fun randomGradientCount(rnd: Random): Int =
    GRADIENT_COUNTS[rnd.nextInt(GRADIENT_COUNTS.size)]

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

    @Test
    fun initialRandom() {
        val rnd = Random(42)
        repeat(300) {
            val name = randomString(rnd)
            assertEquals(initialOracle(name), AvatarDisplayPolicy.initial(name))
        }
    }

    @Test
    fun gradientIndexRandom() {
        val rnd = Random(42)
        repeat(300) {
            val name = randomString(rnd)
            val count = randomGradientCount(rnd)
            val index = AvatarDisplayPolicy.gradientIndex(name, count)
            assertEquals(gradientIndexOracle(name, count), index)
            assertTrue(index in 0 until count)
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // initial() takes the first UTF-16 unit, no trim, no case folding.
        assertEquals("A", AvatarDisplayPolicy.initial("Alice"))
        assertEquals("?", AvatarDisplayPolicy.initial(""))
        assertEquals("张", AvatarDisplayPolicy.initial("张三"))
        assertEquals(" ", AvatarDisplayPolicy.initial(" Bob"))
        // An astral first char yields a lone high surrogate, not the emoji.
        assertEquals("\uD83D", AvatarDisplayPolicy.initial("\uD83D\uDE00x"))
        assertEquals("\uD800", AvatarDisplayPolicy.initial("\uD800"))
        assertEquals("\uDC00", AvatarDisplayPolicy.initial("\uDC00"))
        // Exact gradient indices (hashCode values, not just the range).
        assertEquals(4, AvatarDisplayPolicy.gradientIndex("Alice", 6))
        assertEquals(5, AvatarDisplayPolicy.gradientIndex("Bob", 6))
        assertEquals(1, AvatarDisplayPolicy.gradientIndex("张三", 6))
        assertEquals(0, AvatarDisplayPolicy.gradientIndex("", 6))
        assertEquals(1, AvatarDisplayPolicy.gradientIndex("\uD83D\uDE00emoji", 6))
        assertEquals(2, AvatarDisplayPolicy.gradientIndex("zzzzzzzzzz", 6))
        assertEquals(5, AvatarDisplayPolicy.gradientIndex("user_12345", 6))
        assertEquals(1, AvatarDisplayPolicy.gradientIndex("a", 6))
        // One gradient always yields index zero.
        assertEquals(0, AvatarDisplayPolicy.gradientIndex("Alice", 1))
        assertEquals(0, AvatarDisplayPolicy.gradientIndex("Bob", 1))
        assertEquals(0, AvatarDisplayPolicy.gradientIndex("", 1))
        // Zero divisor throws, like % 0 does.
        assertFailsWith<ArithmeticException> {
            AvatarDisplayPolicy.gradientIndex("Alice", 0)
        }
        // Negative divisors: mod takes the divisor's sign, so it is
        // negative, and the production guard shifts it once more by the
        // (negative) divisor.
        assertEquals(-5, AvatarDisplayPolicy.gradientIndex("Alice", -3))
        assertEquals(-8, AvatarDisplayPolicy.gradientIndex("Alice", -6))
        assertEquals(-132, AvatarDisplayPolicy.gradientIndex("Alice", -100))
        assertEquals(-4, AvatarDisplayPolicy.gradientIndex("Bob", -3))
        assertEquals(-11, AvatarDisplayPolicy.gradientIndex("\uD83D\uDE00emoji", -6))
        assertEquals(-5, AvatarDisplayPolicy.gradientIndex("\uD83D\uDE00emoji", -3))
        assertEquals(-177, AvatarDisplayPolicy.gradientIndex("\uD83D\uDE00emoji", -100))
        assertEquals(-10, AvatarDisplayPolicy.gradientIndex("zzzzzzzzzz", -6))
        // Extreme divisors: the hash fits, so the index is the hash itself.
        assertEquals(63350368, AvatarDisplayPolicy.gradientIndex("Alice", Int.MAX_VALUE))
        assertEquals(63350368, AvatarDisplayPolicy.gradientIndex("Alice", Int.MIN_VALUE))
        assertEquals(0, AvatarDisplayPolicy.gradientIndex("", Int.MIN_VALUE))
    }
}
