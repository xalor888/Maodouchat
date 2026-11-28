package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GroupNamePolicy] (checklist line 931, 31st block).
 * Test-only, zero production changes. The oracle below re-implements trim/take
 * over UTF-16 code units by hand; a Python mini-model differentially checks the
 * oracle transcription against an independent production transcription.
 * Note: Kotlin trim() strips Char.isWhitespace (Java whitespace OR space chars),
 * so NBSP (U+00A0) IS trimmed. This differs from java.lang.String.trim().
 */
class GroupNamePolicyFuzzTest {

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

    // take(n) counts UTF-16 code units: String.length / substring are code-unit based.
    private fun oracleTake(s: String, n: Int): String = s.substring(0, minOf(n, s.length))

    private fun oracleValidate(raw: String): GroupNamePolicy.Validation {
        val trimmed = oracleTrim(raw)
        return when {
            trimmed.isEmpty() -> GroupNamePolicy.Validation.Blank
            trimmed.length > GroupNamePolicy.MAX_LENGTH -> GroupNamePolicy.Validation.TooLong
            else -> GroupNamePolicy.Validation.Valid
        }
    }

    // One-code-point strings (emoji is a surrogate pair, so it cannot be a Char).
    private val alphabet = listOf(
        "a", "z", "A", "Z", "0", "9", " ", "_", "-", "@", "!", "\t", "\n",
        "\u000B", "\u000C", "\u00A0", "你", "好", "é", "\uD83D\uDE00"
    )

    private fun randomRaw(r: Random): String {
        val n = r.nextInt(0, 61)
        return buildString {
            repeat(n) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    @Test
    fun randomFilterNormalizeValidateMatchOracle() {
        val r = Random(3112)
        repeat(300) {
            val raw = randomRaw(r)
            assertEquals(oracleTake(raw, 50), GroupNamePolicy.filterInput(raw), "raw=$raw")
            assertEquals(
                oracleTake(oracleTrim(raw), 50),
                GroupNamePolicy.normalize(raw),
                "raw=$raw"
            )
            assertEquals(oracleValidate(raw), GroupNamePolicy.validate(raw), "raw=$raw")
        }
    }

    @Test
    fun filterAndNormalizeAreIdempotent() {
        val r = Random(3113)
        repeat(300) {
            val raw = randomRaw(r)
            val filtered = GroupNamePolicy.filterInput(raw)
            assertEquals(filtered, GroupNamePolicy.filterInput(filtered))
            val normalized = GroupNamePolicy.normalize(raw)
            // trim-then-take is only idempotent up to a final trim: truncating at
            // 50 units can leave a trailing space that a second pass strips.
            assertEquals(normalized.trim(), GroupNamePolicy.normalize(normalized))
            assertTrue(GroupNamePolicy.normalize(normalized).length <= normalized.length)
            // normalize == filter of the trimmed input (same 50-unit cap, trim first).
            assertEquals(GroupNamePolicy.filterInput(raw.trim()), normalized)
        }
    }

    @Test
    fun filteredValueNeverTooLong() {
        val r = Random(3114)
        repeat(300) {
            val raw = randomRaw(r)
            val filtered = GroupNamePolicy.filterInput(raw)
            assertNotEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate(filtered))
            assertEquals(
                filtered.trim().isEmpty(),
                GroupNamePolicy.validate(filtered) == GroupNamePolicy.Validation.Blank
            )
            assertTrue(filtered.length <= 50)
        }
    }

    @Test
    fun validateOutcomeMatchesTrimmedLength() {
        val r = Random(3115)
        repeat(300) {
            val raw = randomRaw(r)
            val trimmed = oracleTrim(raw)
            when (GroupNamePolicy.validate(raw)) {
                GroupNamePolicy.Validation.Blank -> assertTrue(trimmed.isEmpty(), "raw=$raw")
                GroupNamePolicy.Validation.TooLong -> assertTrue(trimmed.length > 50, "raw=$raw")
                GroupNamePolicy.Validation.Valid -> {
                    assertTrue(trimmed.isNotEmpty(), "raw=$raw")
                    assertTrue(trimmed.length <= 50, "raw=$raw")
                }
            }
        }
    }

    @Test
    fun boundaryPins() {
        assertEquals(GroupNamePolicy.Validation.Valid, GroupNamePolicy.validate("a".repeat(50)))
        assertEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate("a".repeat(51)))
        assertEquals(GroupNamePolicy.Validation.Valid, GroupNamePolicy.validate("  " + "a".repeat(50) + "  "))
        assertEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate("  " + "a".repeat(51) + "  "))
        assertEquals("a".repeat(50), GroupNamePolicy.filterInput("a".repeat(60)))
        assertEquals("  ab  ", GroupNamePolicy.filterInput("  ab  "))
        // NBSP (U+00A0) is a space char, so Kotlin trim strips it: a lone NBSP is blank.
        assertEquals(GroupNamePolicy.Validation.Blank, GroupNamePolicy.validate("\u00A0"))
        assertEquals("", GroupNamePolicy.normalize("\u00A0"))
        // Vertical tab / form feed ARE Kotlin whitespace and get stripped too.
        assertEquals("abc", GroupNamePolicy.normalize("\u000Babc\u000C"))
        // Emoji count as 2 UTF-16 units each: 25 fit, 26 overflow.
        val emoji = "\uD83D\uDE00"
        assertEquals(GroupNamePolicy.Validation.Valid, GroupNamePolicy.validate(emoji.repeat(25)))
        assertEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate(emoji.repeat(26)))
        assertEquals(emoji.repeat(25), GroupNamePolicy.normalize(emoji.repeat(26)))
        assertEquals(emoji.repeat(25), GroupNamePolicy.filterInput(emoji.repeat(26)))
        // Filter keeps surrounding spaces; normalize trims them.
        assertEquals("  ab  ", GroupNamePolicy.filterInput("  ab  "))
        assertEquals("ab", GroupNamePolicy.normalize("  ab  "))
    }
}
