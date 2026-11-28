package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [CaptureAlertPolicy] (checklist line 931,
 * 55th block). Test-only, zero production changes.
 *
 * The oracle re-implements format / parse / isCaptureAlert by hand from
 * the same string primitives (trim / ifBlank / take / substringBefore /
 * substringAfter / startsWith). Random inputs use Random(42), fresh per
 * @Test.
 */
class CaptureAlertPolicyFuzzTest {

// ---------- C3 CaptureAlertPolicy oracle ----------

private fun formatOracle(localUserLabel: String, kind: String = "screenshot"): String {
    val who = localUserLabel.trim().ifBlank { "peer" }.take(32)
    val k = kind.trim().ifBlank { "screenshot" }.take(24)
    return "CAPTURE_ALERT:$k|$who took a $k (privacy alert)"
}

private fun parseOracle(content: String): Pair<String, String>? {
    if (!content.startsWith("CAPTURE_ALERT:")) return null
    val body = content.removePrefix("CAPTURE_ALERT:")
    val kind = body.substringBefore('|').ifBlank { "screenshot" }
    val rest = body.substringAfter('|', body)
    return kind to rest
}

private fun isCaptureAlertOracle(content: String): Boolean = content.startsWith("CAPTURE_ALERT:")

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

private fun randomTab(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> -5; 1 -> -1; 2 -> 0; 3 -> 1; 4 -> 2; 5 -> 3; 6 -> 5; 7 -> 100
    8 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomAlertContent(rnd: Random): String = when (rnd.nextInt(6)) {
    0 -> "CAPTURE_ALERT:" + randomString(rnd, 12) + "|" + randomString(rnd, 20)
    1 -> "CAPTURE_ALERT:" + randomString(rnd, 12)
    2 -> "CAPTURE_ALERT:" + randomString(rnd, 6) + "|" + randomString(rnd, 6) + "|" + randomString(rnd, 6)
    3 -> "CAPTURE_ALERT:"
    4 -> randomString(rnd)
    else -> randomString(rnd, 8) + "CAPTURE_ALERT:" + randomString(rnd, 8)
}

    @Test
    fun formatRandom() {
        val rnd = Random(42)
        repeat(300) {
            val label = randomString(rnd, 48)
            val kind = randomString(rnd, 40)
            assertEquals(formatOracle(label, kind), CaptureAlertPolicy.format(label, kind))
        }
    }

    @Test
    fun parseRandom() {
        val rnd = Random(42)
        repeat(300) {
            val content = randomAlertContent(rnd)
            assertEquals(parseOracle(content), CaptureAlertPolicy.parse(content))
        }
    }

    @Test
    fun isCaptureAlertRandom() {
        val rnd = Random(42)
        repeat(300) {
            val content = randomAlertContent(rnd)
            assertEquals(isCaptureAlertOracle(content), CaptureAlertPolicy.isCaptureAlert(content))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // format: defaults, blank fallbacks, truncation at 32 / 24.
        assertEquals(
            "CAPTURE_ALERT:screenshot|alice took a screenshot (privacy alert)",
            CaptureAlertPolicy.format("alice")
        )
        assertEquals(
            "CAPTURE_ALERT:screenshot|peer took a screenshot (privacy alert)",
            CaptureAlertPolicy.format("", "screenshot")
        )
        assertEquals(
            "CAPTURE_ALERT:screenshot|peer took a screenshot (privacy alert)",
            CaptureAlertPolicy.format("   ", "  ")
        )
        assertEquals(
            "CAPTURE_ALERT:screen|bob took a screen (privacy alert)",
            CaptureAlertPolicy.format("\u00A0bob\u00A0", " screen ")
        )
        val longWho = "a".repeat(40)
        val longKind = "k".repeat(30)
        assertEquals(
            "CAPTURE_ALERT:" + "k".repeat(24) + "|" + "a".repeat(32) +
                " took a " + "k".repeat(24) + " (privacy alert)",
            CaptureAlertPolicy.format(longWho, longKind)
        )
        // A '|' inside kind is not escaped: it flows into the payload.
        assertEquals(
            "CAPTURE_ALERT:screen|recording|bob took a screen|recording (privacy alert)",
            CaptureAlertPolicy.format("bob", "screen|recording")
        )
        // parse: kind is the text before the first '|', rest is the remainder.
        assertEquals(
            "screenshot" to "alice took a screenshot (privacy alert)",
            CaptureAlertPolicy.parse("CAPTURE_ALERT:screenshot|alice took a screenshot (privacy alert)")
        )
        assertEquals(null, CaptureAlertPolicy.parse("hello"))
        assertEquals(null, CaptureAlertPolicy.parse(""))
        assertEquals(null, CaptureAlertPolicy.parse("CAPTURE_ALERT"))
        assertEquals(null, CaptureAlertPolicy.parse("capture_alert:x|y"))
        assertEquals("screenshot" to "", CaptureAlertPolicy.parse("CAPTURE_ALERT:"))
        assertEquals("foo" to "foo", CaptureAlertPolicy.parse("CAPTURE_ALERT:foo"))
        assertEquals("foo" to "", CaptureAlertPolicy.parse("CAPTURE_ALERT:foo|"))
        assertEquals("screenshot" to "x", CaptureAlertPolicy.parse("CAPTURE_ALERT:|x"))
        assertEquals("a" to "b|c", CaptureAlertPolicy.parse("CAPTURE_ALERT:a|b|c"))
        assertEquals("screenshot" to "  ", CaptureAlertPolicy.parse("CAPTURE_ALERT:  |  "))
        // Round trip through format/parse.
        val rendered = CaptureAlertPolicy.format("alice", "screen")
        assertEquals("screen" to "alice took a screen (privacy alert)", CaptureAlertPolicy.parse(rendered))
        // isCaptureAlert is a plain prefix check.
        assertEquals(true, CaptureAlertPolicy.isCaptureAlert("CAPTURE_ALERT:"))
        assertEquals(true, CaptureAlertPolicy.isCaptureAlert("CAPTURE_ALERT:x"))
        assertEquals(false, CaptureAlertPolicy.isCaptureAlert(""))
        assertEquals(false, CaptureAlertPolicy.isCaptureAlert("CAPTURE_ALERT"))
        assertEquals(false, CaptureAlertPolicy.isCaptureAlert("xCAPTURE_ALERT:"))
    }
}
