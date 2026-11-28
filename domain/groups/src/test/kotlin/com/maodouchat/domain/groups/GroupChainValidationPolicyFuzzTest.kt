package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GroupChainValidationPolicy] (checklist line 931, 32nd block).
 * Test-only, zero production changes. The oracle below re-implements trim over
 * UTF-16 code units by hand; a Python mini-model differentially checks the
 * oracle transcription against an independent production transcription.
 * Note the two different blank notions: isNotBlank uses Java Character.isWhitespace
 * (NBSP U+00A0 and NEL U+0085 are NOT whitespace), while trim() only strips
 * chars <= U+0020 (em space U+2003 is NOT stripped).
 */
class GroupChainValidationPolicyFuzzTest {

    // Manual trim: drop leading/trailing chars with code <= ' ' (Kotlin trim semantics).
    private fun oracleTrim(s: String): String {
        var start = 0
        while (start < s.length && s[start] <= ' ') start++
        var end = s.length
        while (end > start && s[end - 1] <= ' ') end--
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
        // NBSP is not Java whitespace and is not trimmed: a NBSP-only title is valid.
        assertTrue(GroupChainValidationPolicy.isValidTitle("\u00A0"))
        // NEL is not Java whitespace either (verified against the JDK): valid as well.
        assertTrue(GroupChainValidationPolicy.isValidTitle("\u0085"))
        // Em space IS Java whitespace, so an em-space-only title is blank.
        assertFalse(GroupChainValidationPolicy.isValidTitle("\u2003"))
        assertFalse(GroupChainValidationPolicy.isValidTitle("   "))
        assertFalse(GroupChainValidationPolicy.isValidTitle(""))
        // Trailing NBSP is not trimmed, so it counts toward the 200 cap.
        assertTrue(GroupChainValidationPolicy.isValidTitle("x".repeat(199) + "\u00A0"))
        assertFalse(GroupChainValidationPolicy.isValidTitle("x".repeat(200) + "\u00A0"))
        // Plain spaces are trimmed before counting.
        assertTrue(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(200) + "  "))
        assertFalse(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(201) + "  "))
        // Em space is not trimmed either: it counts, but a letter keeps it non-blank.
        assertTrue(GroupChainValidationPolicy.isValidEntryContent("\u2003x\u2003"))
        // Topic length counts UTF-16 code units: one emoji is two units.
        assertTrue(GroupChainValidationPolicy.isValidTopic("\uD83D\uDE00".repeat(250)))
        assertFalse(GroupChainValidationPolicy.isValidTopic("\uD83D\uDE00".repeat(251)))
        assertTrue(GroupChainValidationPolicy.isValidEntryContent("x".repeat(500)))
        assertFalse(GroupChainValidationPolicy.isValidEntryContent("x".repeat(501)))
    }
}
