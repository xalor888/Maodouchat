package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GroupChainValidationPolicy] (checklist line 931, 32nd block).
 * Test-only, zero production changes. The oracle below re-implements Kotlin's
 * Char.isWhitespace (Java whitespace OR space chars) over UTF-16 code units by
 * hand. NBSP U+00A0, U+2007, U+202F and the Zs spaces ARE blank/trimmed; NEL
 * U+0085, U+200B and U+FEFF are not.
 */
class GroupChainValidationPolicyFuzzTest {

    // Kotlin Char.isWhitespace == Character.isWhitespace || Character.isSpaceChar.
    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    // Manual trim matching Kotlin String.trim().
    private fun oracleTrim(s: String): String {
        var start = 0
        while (start < s.length && isKotlinWhitespace(s[start])) start++
        var end = s.length
        while (end > start && isKotlinWhitespace(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun oracleIsValidTitle(title: String): Boolean =
        title.any { !it.isWhitespace() } && oracleTrim(title).length <= 200

    private fun oracleIsValidTopic(topic: String): Boolean = topic.length <= 500

    private fun oracleIsValidEntryContent(content: String): Boolean =
        content.any { !it.isWhitespace() } && oracleTrim(content).length <= 500

    private val alphabet = listOf(
        "a", "z", "A", "Z", "0", "9", " ", "_", "-", "@", "\t", "\n",
        "\u000B", "\u000C", "\u001C", "\u0085", "\u00A0", "\u2003", "\u2007",
        "你", "好", "é", "\uD83D\uDE00"
    )

    private fun randomRaw(r: Random, maxLen: Int): String {
        val n = r.nextInt(0, maxLen + 1)
        return buildString {
            repeat(n) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    @Test
    fun randomTitleMatchesOracle() {
        val r = Random(3211)
        repeat(300) {
            // Bias lengths around the 200 boundary after trimming.
            val raw = if (r.nextInt(3) == 0) randomRaw(r, 260)
            else "  " + randomRaw(r, 210) + "  "
            assertEquals(
                oracleIsValidTitle(raw),
                GroupChainValidationPolicy.isValidTitle(raw),
                "raw=$raw"
            )
        }
    }

    @Test
    fun randomTopicMatchesLengthRule() {
        val r = Random(3212)
        repeat(300) {
            val raw = randomRaw(r, 560)
            assertEquals(
                oracleIsValidTopic(raw),
                GroupChainValidationPolicy.isValidTopic(raw),
                "raw=$raw"
            )
        }
        // The topic rule never trims and never rejects blank: blank is valid.
        assertTrue(GroupChainValidationPolicy.isValidTopic("   "))
    }

    @Test
    fun randomEntryMatchesOracle() {
        val r = Random(3213)
        repeat(300) {
            val raw = if (r.nextInt(3) == 0) randomRaw(r, 560)
            else "  " + randomRaw(r, 510) + "  "
            assertEquals(
                oracleIsValidEntryContent(raw),
                GroupChainValidationPolicy.isValidEntryContent(raw),
                "raw=$raw"
            )
        }
    }

    @Test
    fun randomCanJoinMatchesTruthTable() {
        val r = Random(3214)
        repeat(300) {
            val myJoined = r.nextBoolean()
            val active = r.nextBoolean()
            assertEquals(
                !myJoined && active,
                GroupChainValidationPolicy.canJoin(myJoined, active),
                "myJoined=$myJoined active=$active"
            )
        }
        // Exhaustive truth table.
        assertTrue(GroupChainValidationPolicy.canJoin(myJoined = false, active = true))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = true, active = true))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = false, active = false))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = true, active = false))
    }

    @Test
    fun whitespaceAndUtf16BoundaryPins() {
        // NBSP is a Kotlin space char, so a NBSP-only title is blank.
        assertFalse(GroupChainValidationPolicy.isValidTitle("\u00A0"))
        // NEL is not Kotlin whitespace: a NEL-only title is valid.
        assertTrue(GroupChainValidationPolicy.isValidTitle("\u0085"))
        // Em space IS Kotlin whitespace, so an em-space-only title is blank.
        assertFalse(GroupChainValidationPolicy.isValidTitle("\u2003"))
        assertFalse(GroupChainValidationPolicy.isValidTitle("   "))
        assertFalse(GroupChainValidationPolicy.isValidTitle(""))
        // Trailing NBSP is trimmed before counting.
        assertTrue(GroupChainValidationPolicy.isValidTitle("x".repeat(199) + "\u00A0"))
        assertTrue(GroupChainValidationPolicy.isValidTitle("x".repeat(200) + "\u00A0"))
        assertFalse(GroupChainValidationPolicy.isValidTitle("x".repeat(201) + "\u00A0"))
        // Plain spaces are trimmed before counting.
        assertTrue(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(200) + "  "))
        assertFalse(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(201) + "  "))
        // Em space is trimmed, but the letter keeps the content non-blank.
        assertTrue(GroupChainValidationPolicy.isValidEntryContent("\u2003x\u2003"))
        // Topic length counts UTF-16 code units: one emoji is two units.
        assertTrue(GroupChainValidationPolicy.isValidTopic("\uD83D\uDE00".repeat(250)))
        assertFalse(GroupChainValidationPolicy.isValidTopic("\uD83D\uDE00".repeat(251)))
        assertTrue(GroupChainValidationPolicy.isValidEntryContent("x".repeat(500)))
        assertFalse(GroupChainValidationPolicy.isValidEntryContent("x".repeat(501)))
    }
}
