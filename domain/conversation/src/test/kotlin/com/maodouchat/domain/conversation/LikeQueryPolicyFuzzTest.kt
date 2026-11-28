package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [LikeQueryPolicy] (checklist line 931, 44th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements the escaper as an explicit char loop with the same
 * three-char escape table (\, %, _), and reproduces the one asymmetry between
 * the two entry points: escapeForContains collapses blank input to "" while
 * escapeForPrefix keeps it verbatim. Both oracle and production call Kotlin's
 * isBlank(), whose whitespace set is Character.isWhitespace plus the three
 * non-breaking spaces U+00A0/U+2007/U+202F (NEL U+0085, ZWSP and BOM are NOT
 * blank) — verified against real kotlin-stdlib 2.4.0. A Python mini-model
 * differentially checks the oracle against Fuzz44Check on 3000 random strings
 * (0 diffs), including round-trip unescape and no-bare-special invariants.
 */
class LikeQueryPolicyFuzzTest {

    private fun oracleEscape(raw: String, blankToEmpty: Boolean): String {
        if (blankToEmpty && raw.isBlank()) return ""
        val sb = StringBuilder(raw.length + 4)
        for (ch in raw) {
            if (ch == '\\' || ch == '%' || ch == '_') sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun oracleEscapeForContains(raw: String): String = oracleEscape(raw, blankToEmpty = true)

    private fun oracleEscapeForPrefix(raw: String): String = oracleEscape(raw, blankToEmpty = false)

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length &&
                (s[i + 1] == '\\' || s[i + 1] == '%' || s[i + 1] == '_')
            ) {
                sb.append(s[i + 1])
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun hasNoBareSpecials(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                if (i + 1 >= s.length ||
                    (s[i + 1] != '\\' && s[i + 1] != '%' && s[i + 1] != '_')
                ) return false
                i += 2
            } else if (ch == '%' || ch == '_') {
                return false
            } else {
                i++
            }
        }
        return true
    }

    private val escapeAlphabet = listOf(
        "a", "b", "A", "B", "z", "Z", "0", "1", "9", " ", "%", "_", "\\",
        "-", "!", "@", "#", "[", "]", "'", ":", ";", ",", ".", "\t", "\n",
        "\u000B", "\u000C", "\r", "\u001C", "\u001D", "\u001E", "\u001F",
        "\u0085", "\u00A0", "\u1680", "\u2000", "\u2001", "\u2007", "\u200B",
        "\u2028", "\u2029", "\u202F", "\u205F", "\u3000", "\uFEFF", "\u00AD",
        "中", "文", "拍", "一", "\u0130", "\u00DF", "\uFB00", "\u00C5",
        "\u03A3", "\uD801\uDC00", "\u00B5"
    )

    private fun randomEscapeString(rnd: Random): String {
        val n = rnd.nextInt(0, 13)
        val alpha = if (rnd.nextInt(100) < 25) {
            listOf("%", "_", "\\", " ") +
                listOf("\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", "\t", "\n")
        } else {
            escapeAlphabet
        }
        return (0 until n).joinToString("") { alpha.random(rnd) }
    }

    @Test
    fun escapeForContainsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val raw = randomEscapeString(rnd)
            assertEquals(
                oracleEscapeForContains(raw),
                LikeQueryPolicy.escapeForContains(raw),
                "raw=<$raw>"
            )
        }
    }

    @Test
    fun escapeForPrefixRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val raw = randomEscapeString(rnd)
            assertEquals(
                oracleEscapeForPrefix(raw),
                LikeQueryPolicy.escapeForPrefix(raw),
                "raw=<$raw>"
            )
        }
    }

    @Test
    fun escapeInvariantsRandom() {
        val rnd = Random(42)
        repeat(200) {
            val raw = randomEscapeString(rnd)
            val escapedContains = LikeQueryPolicy.escapeForContains(raw)
            val escapedPrefix = LikeQueryPolicy.escapeForPrefix(raw)
            assertEquals(oracleEscapeForContains(raw), escapedContains, "raw=<$raw>")
            assertEquals(oracleEscapeForPrefix(raw), escapedPrefix, "raw=<$raw>")
            // Round-trip: unescaping restores the input (except Contains on blank input).
            assertEquals(raw, unescape(escapedPrefix), "prefix round-trip raw=<$raw>")
            if (!raw.isBlank()) {
                assertEquals(raw, unescape(escapedContains), "contains round-trip raw=<$raw>")
            } else {
                assertEquals("", escapedContains, "blank contains raw=<$raw>")
            }
            // No bare % or _ survives; every backslash starts a valid escape.
            assertTrue(hasNoBareSpecials(escapedContains), "raw=<$raw>")
            assertTrue(hasNoBareSpecials(escapedPrefix), "raw=<$raw>")
            // Length grows by exactly one per escaped char.
            val specials = raw.count { it == '\\' || it == '%' || it == '_' }
            assertEquals(raw.length + specials, escapedPrefix.length, "raw=<$raw>")
            if (!raw.isBlank()) {
                assertEquals(raw.length + specials, escapedContains.length, "raw=<$raw>")
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        assertEquals('\\', LikeQueryPolicy.ESCAPE_CHAR)

        // Blank collapses for Contains, is kept verbatim for Prefix.
        assertEquals("", LikeQueryPolicy.escapeForContains(""))
        assertEquals("", LikeQueryPolicy.escapeForPrefix(""))
        assertEquals("", LikeQueryPolicy.escapeForContains(" "))
        assertEquals(" ", LikeQueryPolicy.escapeForPrefix(" "))
        assertEquals("", LikeQueryPolicy.escapeForContains("   "))
        assertEquals("   ", LikeQueryPolicy.escapeForPrefix("   "))
        assertEquals("", LikeQueryPolicy.escapeForContains("\t"))
        assertEquals("\t", LikeQueryPolicy.escapeForPrefix("\t"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\n"))
        assertEquals("", LikeQueryPolicy.escapeForContains(" \t\n "))
        assertEquals(" \t\n ", LikeQueryPolicy.escapeForPrefix(" \t\n "))

        // Kotlin isBlank counts the three non-breaking spaces as blank, but NOT
        // NEL, ZWSP, BOM or soft hyphen (unlike Python str.isspace()).
        assertEquals("", LikeQueryPolicy.escapeForContains("\u00A0"))
        assertEquals("\u00A0", LikeQueryPolicy.escapeForPrefix("\u00A0"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\u2007"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\u202F"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\u1680"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\u3000"))
        assertEquals("\u0085", LikeQueryPolicy.escapeForContains("\u0085"))
        assertEquals("\u200B", LikeQueryPolicy.escapeForContains("\u200B"))
        assertEquals("\uFEFF", LikeQueryPolicy.escapeForContains("\uFEFF"))
        assertEquals("\u00AD", LikeQueryPolicy.escapeForContains("\u00AD"))

        // The three specials each gain one backslash; already-escaped input doubles.
        assertEquals("\\%", LikeQueryPolicy.escapeForContains("%"))
        assertEquals("\\_", LikeQueryPolicy.escapeForContains("_"))
        assertEquals("\\\\", LikeQueryPolicy.escapeForContains("\\"))
        assertEquals("\\%\\_\\%", LikeQueryPolicy.escapeForContains("%_%"))
        assertEquals("\\\\\\%", LikeQueryPolicy.escapeForContains("\\%"))
        assertEquals("a\\%b\\_c\\\\d", LikeQueryPolicy.escapeForContains("a%b_c\\d"))
        assertEquals("a\\%b\\_c\\\\d", LikeQueryPolicy.escapeForPrefix("a%b_c\\d"))
        assertEquals("\\\\\\\\", LikeQueryPolicy.escapeForContains("\\\\"))
        assertEquals("\\%\\%\\_\\_\\\\\\\\", LikeQueryPolicy.escapeForContains("%%__\\\\"))

        // Non-blank input is identical for both entry points; plain text is untouched.
        assertEquals("user\\_id", LikeQueryPolicy.escapeForContains("user_id"))
        assertEquals("user\\_id", LikeQueryPolicy.escapeForPrefix("user_id"))
        assertEquals("100\\%", LikeQueryPolicy.escapeForContains("100%"))
        assertEquals("a\\\\b", LikeQueryPolicy.escapeForPrefix("a\\b"))
        assertEquals("hello", LikeQueryPolicy.escapeForContains("hello"))
        assertEquals("拍一拍", LikeQueryPolicy.escapeForContains("拍一拍"))
        assertEquals("a b", LikeQueryPolicy.escapeForContains("a b"))
        assertEquals(" hello ", LikeQueryPolicy.escapeForContains(" hello "))
        assertEquals("用户\\_123\\%", LikeQueryPolicy.escapeForPrefix("用户_123%"))
        assertEquals("a[b]c'd:e;f", LikeQueryPolicy.escapeForContains("a[b]c'd:e;f"))

        // Escaping is not idempotent: a second pass escapes the added backslashes too.
        assertEquals(
            "a\\\\\\%b\\\\\\_c",
            LikeQueryPolicy.escapeForContains(LikeQueryPolicy.escapeForContains("a%b_c"))
        )
    }
}
