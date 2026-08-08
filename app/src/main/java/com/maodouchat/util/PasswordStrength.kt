package com.maodouchat.util

/**
 * 密码强度评估（不依赖后端，本地即可实时反馈）
 *
 * 评分规则（满分 4）：
 * - 长度 ≥ 8
 * - 包含数字
 * - 包含小写字母
 * - 包含大写字母
 * - 包含特殊字符（!@#$%^&*()_+-=[]{};:'",.<>/?\\|） 加 1
 * 强度等级：WEAK / FAIR / STRONG / VERY_STRONG
 */
object PasswordStrength {

    enum class Level { WEAK, FAIR, STRONG, VERY_STRONG }
    enum class Suggestion { ENTER_PASSWORD, USE_MINIMUM_LENGTH, ADD_DIGIT, ADD_LOWERCASE, ADD_UPPERCASE }

    data class Result(val score: Int, val level: Level, val suggestions: List<Suggestion>)

    fun evaluate(password: String): Result {
        if (password.isBlank()) {
            return Result(0, Level.WEAK, listOf(Suggestion.ENTER_PASSWORD))
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
        return Result(score, level, suggestions)
    }
}
