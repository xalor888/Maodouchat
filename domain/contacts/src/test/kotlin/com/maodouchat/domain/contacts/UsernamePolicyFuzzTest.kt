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
            assertEquals("raw=$raw", oracleFilter(raw), UsernamePolicy.filterInput(raw))
        }
    }

    @Test
    fun `random validate matches oracle and length wins over charset`() {
        val r = Random(3117)
        repeat(300) {
            val raw = randomRaw(r)
            assertEquals("raw=$raw", oracleValidate(raw), UsernamePolicy.validate(raw))
            if (raw.length < UsernamePolicy.MIN_LENGTH) {
                assertEquals(Validation.TooShort, UsernamePolicy.validate(raw))
            }
        }
    }

    @Test
    fun `filtered value only gains combining marks from lowercase expansion`() {
        val r = Random(3118)
        repeat(300) {
            val raw = randomRaw(r)
            val filtered = UsernamePolicy.filterInput(raw)
            // filterInput 先过滤再 lowercase：除 İ(U+0130) 展开出的组合点 U+0307 外，
            // 结果只会包含 [A-Za-z0-9_-] 与其它 Unicode 字母数字。
            if ('\u0307' in filtered) return@repeat
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
            // 幂等只在这两条前提同时成立时成立：结果不超 50 单元，且 lowercase
            // 没有引入会被下一轮过滤掉的组合点（如 İ -> i + U+0307）。
            if (once.length > UsernamePolicy.MAX_LENGTH) return@repeat
            if (!once.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return@repeat
            assertEquals("raw=$raw", once, UsernamePolicy.filterInput(once))
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
