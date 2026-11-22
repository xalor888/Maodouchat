package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PasswordStrength.evaluate 的随机 oracle fuzz。
 * oracle 按生产源码逐字转录；随机输入固定种子，可复现。
 */
class PasswordStrengthFuzzTest {

    private val random = Random(2026112203)

    // ---- evaluate oracle：满分 5（长度≥8、数字、小写、大写、特殊字符各 +1） ----

    private fun expectedEvaluate(password: String): PasswordStrengthResult {
        if (password.isBlank()) {
            return PasswordStrengthResult(0, Level.WEAK, listOf(Suggestion.ENTER_PASSWORD))
        }
        var score = 0
        val suggestions = mutableListOf<Suggestion>()
        if (password.length >= 8) score++ else suggestions += Suggestion.USE_MINIMUM_LENGTH
        if (password.any { it.isDigit() }) score++ else suggestions += Suggestion.ADD_DIGIT
        if (password.any { it.isLowerCase() }) score++ else suggestions += Suggestion.ADD_LOWERCASE
        if (password.any { it.isUpperCase() }) score++ else suggestions += Suggestion.ADD_UPPERCASE
        if (password.any { !it.isLetterOrDigit() }) score++
        val level = when {
            score <= 1 -> Level.WEAK
            score == 2 -> Level.FAIR
            score == 3 -> Level.STRONG
            else -> Level.VERY_STRONG
        }
        return PasswordStrengthResult(score, level, suggestions)
    }

    private fun randomPassword(): String {
        val alphabet = "abcXYZ019!@# _\t" + "éüλ中\u0663\u00B2\u00A0\u00DF\uD83D\uDE00"
        val length = random.nextInt(0, 21)
        return buildString {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    @Test
    fun evaluateMatchesTranscribedOracle() {
        repeat(300) {
            val password = randomPassword()
            assertEquals(expectedEvaluate(password), PasswordStrength.evaluate(password))
        }
    }

    @Test
    fun scoreAndLevelPinnedForAllScores() {
        assertEquals(
            PasswordStrengthResult(0, Level.WEAK, listOf(Suggestion.ENTER_PASSWORD)),
            PasswordStrength.evaluate("")
        )
        assertEquals(
            PasswordStrengthResult(
                1, Level.WEAK,
                listOf(Suggestion.USE_MINIMUM_LENGTH, Suggestion.ADD_DIGIT, Suggestion.ADD_UPPERCASE)
            ),
            PasswordStrength.evaluate("a")
        )
        assertEquals(
            PasswordStrengthResult(
                2, Level.FAIR,
                listOf(Suggestion.ADD_DIGIT, Suggestion.ADD_UPPERCASE)
            ),
            PasswordStrength.evaluate("abcdefgh")
        )
        assertEquals(
            PasswordStrengthResult(3, Level.STRONG, listOf(Suggestion.ADD_DIGIT)),
            PasswordStrength.evaluate("Abcdefgh")
        )
        assertEquals(
            PasswordStrengthResult(4, Level.VERY_STRONG, emptyList()),
            PasswordStrength.evaluate("Abcdef12")
        )
        assertEquals(
            PasswordStrengthResult(5, Level.VERY_STRONG, emptyList()),
            PasswordStrength.evaluate("Abcdef1!")
        )
    }

    @Test
    fun unicodeDigitAndSpecialSemantics() {
        // 阿拉伯数字 ٣ 是 Nd 类：算数字、不算特殊字符。
        assertEquals(
            PasswordStrengthResult(3, Level.STRONG, listOf(Suggestion.ADD_UPPERCASE)),
            PasswordStrength.evaluate("aaaaaaaa\u0663")
        )
        // 上标 ² 是 No 类：不算数字、也不算字母数字，故算特殊字符。
        assertEquals(
            PasswordStrengthResult(
                3, Level.STRONG,
                listOf(Suggestion.ADD_DIGIT, Suggestion.ADD_UPPERCASE)
            ),
            PasswordStrength.evaluate("aaaaaaaa\u00B2")
        )
        // NBSP 不是 Java 空白：单个 NBSP 不走 blank 分支，自身算特殊字符。
        assertEquals(
            PasswordStrengthResult(
                1, Level.WEAK,
                listOf(
                    Suggestion.USE_MINIMUM_LENGTH, Suggestion.ADD_DIGIT,
                    Suggestion.ADD_LOWERCASE, Suggestion.ADD_UPPERCASE
                )
            ),
            PasswordStrength.evaluate("\u00A0")
        )
        // 4 个 emoji 按 UTF-16 计长度 8；代理项不算字母数字，故算特殊字符。
        assertEquals(
            PasswordStrengthResult(
                2, Level.FAIR,
                listOf(Suggestion.ADD_DIGIT, Suggestion.ADD_LOWERCASE, Suggestion.ADD_UPPERCASE)
            ),
            PasswordStrength.evaluate("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00")
        )
        // ß 是小写字母；超长输入只按规则计分，不抛异常。
        assertEquals(
            PasswordStrengthResult(
                2, Level.FAIR,
                listOf(Suggestion.ADD_DIGIT, Suggestion.ADD_UPPERCASE)
            ),
            PasswordStrength.evaluate("\u00DF\u00DF\u00DF\u00DF\u00DF\u00DF\u00DF\u00DF")
        )
        assertEquals(2, PasswordStrength.evaluate("a".repeat(10000)).score)
    }

    @Test
    fun addingMissingClassRaisesScoreByExactlyOne() {
        repeat(100) {
            var base = randomPassword()
            if (base.length < 8) base += "a".repeat(8 - base.length)
            val before = PasswordStrength.evaluate(base)
            val missing = listOf(
                '5' to Suggestion.ADD_DIGIT,
                'a' to Suggestion.ADD_LOWERCASE,
                'A' to Suggestion.ADD_UPPERCASE,
                '!' to null
            )
            for ((ch, suggestion) in missing) {
                val hasClass = when (ch) {
                    '5' -> base.any { it.isDigit() }
                    'a' -> base.any { it.isLowerCase() }
                    'A' -> base.any { it.isUpperCase() }
                    else -> base.any { !it.isLetterOrDigit() }
                }
                if (hasClass) continue
                val after = PasswordStrength.evaluate(base + ch)
                assertEquals(before.score + 1, after.score)
                if (suggestion != null) assertFalse(after.suggestions.contains(suggestion))
                assertTrue(after.level.ordinal >= before.level.ordinal)
            }
        }
    }

    @Test
    fun blankInputsAlwaysWeakWithEnterHint() {
        val blanks = listOf(
            "", " ", "  \t\n\r", "\u000B\u000C", "\u001C\u001D",
            "\u0085", "\u2003\u2007", "\u3000"
        )
        for (blank in blanks) {
            assertEquals(
                PasswordStrengthResult(0, Level.WEAK, listOf(Suggestion.ENTER_PASSWORD)),
                PasswordStrength.evaluate(blank)
            )
        }
        // 含任一非空白字符即不走 blank 分支。
        assertFalse(PasswordStrength.evaluate("  a  ").suggestions.contains(Suggestion.ENTER_PASSWORD))
    }
}
