package com.maodouchat.domain.contacts

import com.maodouchat.domain.contacts.UsernamePolicy.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzz / random-oracle tests for [UsernamePolicy] (checklist line 931, 31st block).
 * Test-only, zero production changes. The oracle below re-implements the
 * filter -> take(50) -> lowercase -> removePrefix("@") pipeline; a Python
 * mini-model differentially checks the oracle transcription against an
 * independent production transcription (including Java isLetterOrDigit
 * semantics via Unicode categories).
 *
 * Quirks pinned here (not fixed): filterInput lowercases AFTER take(50), so
 * "İ" (U+0130, lowercases to 2 UTF-16 units) can push the output past 50
 * chars; removePrefix("@") is dead code because '@' is filtered out first.
 */
class UsernamePolicyFuzzTest {

    private fun oracleFilter(value: String): String =
        value.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
            .take(UsernamePolicy.MAX_LENGTH).lowercase().removePrefix("@")

    private fun oracleValidate(username: String): Validation = when {
        username.length < UsernamePolicy.MIN_LENGTH -> Validation.TooShort
        !username.all { it.isLetterOrDigit() || it == '_' || it == '-' } ->
            Validation.InvalidCharacters
        else -> Validation.Valid
    }

    // One-code-point strings (emoji is a surrogate pair, so it cannot be a Char).
    private val alphabet = listOf(
        "a", "z", "A", "Z", "0", "9", "_", "-", "@", "!", " ", ".", "#",
        "é", "ü", "İ", "ß", "你", "好", "٣", "²", "😀", "\t"
    )

    private fun randomRaw(r: Random): String {
        val n = r.nextInt(0, 61)
        return buildString {
            repeat(n) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    @Test
    fun `random filter matches oracle`() {
        val r = Random(3116)
        repeat(300) {
            val raw = randomRaw(r)
            assertEquals(oracleFilter(raw), UsernamePolicy.filterInput(raw), "raw=$raw")
        }
    }

    @Test
    fun `random validate matches oracle and length wins over charset`() {
        val r = Random(3117)
        repeat(300) {
            val raw = randomRaw(r)
            assertEquals(oracleValidate(raw), UsernamePolicy.validate(raw), "raw=$raw")
            if (raw.length < UsernamePolicy.MIN_LENGTH) {
                assertEquals(Validation.TooShort, UsernamePolicy.validate(raw))
            }
        }
    }

    @Test
    fun `filtered value never has invalid characters`() {
        val r = Random(3118)
        repeat(300) {
            val raw = randomRaw(r)
            val filtered = UsernamePolicy.filterInput(raw)
            assertNotEquals(Validation.InvalidCharacters, UsernamePolicy.validate(filtered))
            assertEquals(
                filtered.length >= UsernamePolicy.MIN_LENGTH,
                UsernamePolicy.validate(filtered) == Validation.Valid
            )
            assertTrue(!filtered.startsWith("@"))
        }
    }

    @Test
    fun `filter is idempotent once output fits fifty units`() {
        val r = Random(3119)
        repeat(300) {
            val raw = randomRaw(r)
            val once = UsernamePolicy.filterInput(raw)
            if (once.length <= UsernamePolicy.MAX_LENGTH) {
                assertEquals(once, UsernamePolicy.filterInput(once), "raw=$raw")
            }
        }
    }

    @Test
    fun `boundary pins`() {
        // '@' is filtered before truncation, so it never occupies a slot.
        assertEquals("ada", UsernamePolicy.filterInput("@Ada!#%"))
        assertEquals("a".repeat(50), UsernamePolicy.filterInput("@" + "A".repeat(60)))
        assertEquals("", UsernamePolicy.filterInput("!!!"))
        // Length check wins over charset check.
        assertEquals(Validation.TooShort, UsernamePolicy.validate("a!"))
        assertEquals(Validation.InvalidCharacters, UsernamePolicy.validate("ab!"))
        assertEquals(Validation.Valid, UsernamePolicy.validate("a-b_c9"))
        // Lowercase AFTER take(50): İ expands to 2 units, output can exceed 50.
        assertEquals(100, UsernamePolicy.filterInput("İ".repeat(50)).length)
        assertEquals("i\u0307", UsernamePolicy.filterInput("İ"))
        assertEquals(Validation.TooShort, UsernamePolicy.validate("İİ"))
        assertEquals(Validation.Valid, UsernamePolicy.validate("İİİ"))
        // Second filter pass drops the combining dot (U+0307 is not a letter/digit).
        assertEquals("i".repeat(50), UsernamePolicy.filterInput(UsernamePolicy.filterInput("İ".repeat(50))))
        // Arabic-Indic digit is a letter-or-digit (kept); superscript two is not (dropped).
        assertEquals("٣", UsernamePolicy.filterInput("٣"))
        assertEquals("", UsernamePolicy.filterInput("²"))
        assertEquals(Validation.Valid, UsernamePolicy.validate("٣٣٣"))
        // ASCII-only truncation before lowercasing stays at 50.
        assertEquals("a".repeat(50), UsernamePolicy.filterInput("A".repeat(60)))
    }
}
