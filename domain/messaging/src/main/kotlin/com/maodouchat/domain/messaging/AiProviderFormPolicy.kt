package com.maodouchat.domain.messaging

// AI 服务商表单：13 个输入框上限与保存时归一化（逐字平移自 SettingsAiPrivacy）。
object AiProviderFormPolicy {
    fun clipName(input: String): String = input.take(80)
    fun clipBaseUrl(input: String): String = input.take(240)
    fun clipModel(input: String): String = input.take(80)
    fun clipApiKey(input: String): String = input.take(200)
    fun clipOrganization(input: String): String = input.take(80)
    fun clipAnthropicVersion(input: String): String = input.take(40)
    fun clipExtraHeaders(input: String): String = input.take(1_000)
    fun clipContextWindow(input: String): String = input.filter(Char::isDigit).take(8)
    fun clipHistoryLimit(input: String): String = input.filter(Char::isDigit).take(3)
    fun clipMaxTokens(input: String): String = input.filter(Char::isDigit).take(6)
    fun clipTemperature(input: String): String = input.take(8)
    fun clipTopP(input: String): String = input.take(8)
    fun clipTimeout(input: String): String = input.filter(Char::isDigit).take(3)

    fun normalizeName(input: String, fallback: String): String = input.ifBlank { fallback }
    fun normalizeBaseUrl(input: String): String = input.trim().trimEnd('/')
    fun normalizeAnthropicVersion(input: String): String = input.trim().ifBlank { "2023-06-01" }
    fun normalizeExtraHeaders(input: String): String = input.trim().ifBlank { "{}" }
    fun parseTemperature(input: String): Double? = input.trim().toDoubleOrNull()
    fun parseTopP(input: String): Double? = input.trim().toDoubleOrNull()
    fun parseMaxTokens(input: String): Int = input.toIntOrNull() ?: 4_096
    fun parseContextWindow(input: String): Int = input.toIntOrNull() ?: 128_000
    fun parseHistoryLimit(input: String): Int = input.toIntOrNull() ?: 24
    fun parseTimeout(input: String): Int = input.toIntOrNull() ?: 120
}
