package com.maodouchat.util

enum class Level { WEAK, FAIR, STRONG, VERY_STRONG }
enum class Suggestion { ENTER_PASSWORD, USE_MINIMUM_LENGTH, ADD_DIGIT, ADD_LOWERCASE, ADD_UPPERCASE }

data class PasswordStrengthResult(val score: Int, val level: Level, val suggestions: List<Suggestion>)
