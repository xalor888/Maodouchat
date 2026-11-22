package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [UserIdDisplayPolicy] (checklist line 931,
 * 56th block). Test-only, zero production changes.
 *
 * The oracle re-implements abbreviate by hand: length / take / takeLast
 * all count UTF-16 code units, so one astral char costs 2. Random inputs
 * use Random(42), fresh per @Test.
 */
class UserIdDisplayPolicyFuzzTest {

// ---------- C3 UserIdDisplayPolicy oracle ----------

private fun abbreviateOracle(userId: String): String =
    if (userId.length > 16) userId.take(8) + "…" + userId.takeLast(4) else userId

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

private fun randomNullableString(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else randomString(rnd)

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

// User ids cluster around the 16-code-unit abbreviate boundary.
private fun randomUserId(rnd: Random): String {
    val len = when (rnd.nextInt(10)) {
        0 -> 0; 1 -> 1; 2 -> 8; 3 -> 15; 4 -> 16; 5 -> 17; 6 -> 18; 7 -> 24
        8 -> 32
        else -> rnd.nextInt(0, 41)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

    @Test
    fun abbreviateRandom() {
        val rnd = Random(42)
        repeat(300) {
            val userId = randomUserId(rnd)
            assertEquals(abbreviateOracle(userId), UserIdDisplayPolicy.abbreviate(userId))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // 16 code units or fewer pass through untouched.
        assertEquals("", UserIdDisplayPolicy.abbreviate(""))
        assertEquals("abc", UserIdDisplayPolicy.abbreviate("abc"))
        assertEquals("x".repeat(16), UserIdDisplayPolicy.abbreviate("x".repeat(16)))
        assertEquals("0123456789abcdef", UserIdDisplayPolicy.abbreviate("0123456789abcdef"))
        // 17+ code units: first 8 + "…" + last 4.
        assertEquals("01234567…nopq", UserIdDisplayPolicy.abbreviate("0123456789abcdefghijklmnopq"))
        assertEquals("abcdefgh…wxyz", UserIdDisplayPolicy.abbreviate("abcdefghijklmnopqrstuvwxyz"))
        // Boundary counts UTF-16 code units: 8 astral chars are 16 units
        // (kept), 9 astral chars are 18 units (abbreviated, 4 + 2 emoji).
        val emoji8 = "\uD83D\uDE00".repeat(8)
        val emoji9 = "\uD83D\uDE00".repeat(9)
        assertEquals(emoji8, UserIdDisplayPolicy.abbreviate(emoji8))
        assertEquals("\uD83D\uDE00".repeat(4) + "…" + "\uD83D\uDE00".repeat(2), UserIdDisplayPolicy.abbreviate(emoji9))
        // A lone surrogate counts as one unit and survives the cut.
        val lone = "01234567\uD800" + "abcdefgh"
        assertEquals("01234567…efgh", UserIdDisplayPolicy.abbreviate(lone))
        // Whitespace inside the id is not special-cased: 20 units ->
        // take(8) is "  abcdef", takeLast(4) is "op  ".
        assertEquals("  abcdef…op  ", UserIdDisplayPolicy.abbreviate("  abcdefghijklmnop  "))
    }
}
