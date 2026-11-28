package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [CustomStatusPolicy] (checklist line 931,
 * 56th block). Test-only, zero production changes.
 *
 * The oracle re-implements normalize / isValid / visibleStatus by hand.
 * Note isValid does NOT trim: it is a raw length check on the untrimmed
 * value (a blank 81-char string is invalid). Random inputs use Random(42),
 * fresh per @Test.
 */
class CustomStatusPolicyFuzzTest {

// ---------- C1 CustomStatusPolicy oracle ----------

private fun normalizeOracle(raw: String?): String =
    raw.orEmpty().trim().take(80)

private fun isValidOracle(raw: String?): Boolean =
    raw.orEmpty().length <= 80

private fun visibleStatusOracle(status: String?, showStatus: Boolean): String =
    if (!showStatus) "" else normalizeOracle(status)

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
    fun normalizeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = if (rnd.nextInt(4) == 0) null else randomString(rnd, 120)
            assertEquals(normalizeOracle(raw), CustomStatusPolicy.normalize(raw))
        }
    }

    @Test
    fun isValidRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = if (rnd.nextInt(4) == 0) null else randomString(rnd, 120)
            assertEquals(isValidOracle(raw), CustomStatusPolicy.isValid(raw))
        }
    }

    @Test
    fun visibleStatusRandom() {
        val rnd = Random(42)
        repeat(300) {
            val status = if (rnd.nextInt(4) == 0) null else randomString(rnd, 120)
            val show = rnd.nextBoolean()
            assertEquals(visibleStatusOracle(status, show), CustomStatusPolicy.visibleStatus(status, show))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // normalize: null -> "", trims (NBSP trims, NEL does not), take(80).
        assertEquals("", CustomStatusPolicy.normalize(null))
        assertEquals("", CustomStatusPolicy.normalize(""))
        assertEquals("x", CustomStatusPolicy.normalize("  x  "))
        assertEquals("x", CustomStatusPolicy.normalize("\u00A0x\u00A0"))
        assertEquals("\u0085x\u0085", CustomStatusPolicy.normalize("\u0085x\u0085"))
        assertEquals("", CustomStatusPolicy.normalize("   "))
        assertEquals("x".repeat(80), CustomStatusPolicy.normalize("x".repeat(81)))
        assertEquals("x".repeat(80), CustomStatusPolicy.normalize("x".repeat(80)))
        assertEquals("x".repeat(80), CustomStatusPolicy.normalize("  " + "x".repeat(90)))
        // 40 astral chars are exactly 80 code units; the 41st is cut.
        val emoji40 = "\uD83D\uDE00".repeat(40)
        val emoji41 = "\uD83D\uDE00".repeat(41)
        assertEquals(emoji40, CustomStatusPolicy.normalize(emoji40))
        assertEquals(emoji40, CustomStatusPolicy.normalize(emoji41))
        // isValid: raw length check, no trim; null counts as empty.
        assertEquals(true, CustomStatusPolicy.isValid(null))
        assertEquals(true, CustomStatusPolicy.isValid(""))
        assertEquals(true, CustomStatusPolicy.isValid("x".repeat(80)))
        assertEquals(false, CustomStatusPolicy.isValid("x".repeat(81)))
        assertEquals(false, CustomStatusPolicy.isValid(" ".repeat(81)))
        assertEquals(true, CustomStatusPolicy.isValid(" ".repeat(80)))
        assertEquals(true, CustomStatusPolicy.isValid(emoji40))
        assertEquals(false, CustomStatusPolicy.isValid(emoji41))
        // visibleStatus: hidden or null status shows nothing.
        assertEquals("", CustomStatusPolicy.visibleStatus("x", false))
        assertEquals("", CustomStatusPolicy.visibleStatus(null, true))
        assertEquals("", CustomStatusPolicy.visibleStatus("   ", true))
        assertEquals("x", CustomStatusPolicy.visibleStatus("  x  ", true))
        assertEquals("x".repeat(80), CustomStatusPolicy.visibleStatus("x".repeat(90), true))
        // PRESETS: the 24 wire values, each short and non-blank.
        assertEquals(24, CustomStatusPolicy.PRESETS.size)
        assertEquals(true, CustomStatusPolicy.PRESETS.contains("在线"))
        for (preset in CustomStatusPolicy.PRESETS) {
            assertEquals(true, CustomStatusPolicy.isValid(preset))
            assertEquals(preset, CustomStatusPolicy.normalize(preset))
        }
    }
}
