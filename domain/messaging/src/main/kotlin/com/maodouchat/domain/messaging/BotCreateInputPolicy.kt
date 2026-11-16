package com.maodouchat.domain.messaging

// 开发者 Bot 创建表单输入清洗：名称截断、用户名只留字母数字下划线并小写、
// webhook 地址截断与 http(s) 校验（逐字平移自 DeveloperBotsScreen）。
object BotCreateInputPolicy {
    const val NAME_MAX_LENGTH = 120
    const val USERNAME_MAX_LENGTH = 32
    const val WEBHOOK_URL_MAX_LENGTH = 500

    fun clipName(input: String): String = input.take(NAME_MAX_LENGTH)

    fun sanitizeUsername(input: String): String =
        input.filter { it.isLetterOrDigit() || it == '_' }.take(USERNAME_MAX_LENGTH).lowercase()

    fun clipWebhookUrl(input: String): String = input.take(WEBHOOK_URL_MAX_LENGTH)

    fun isValidWebhookUrl(raw: String): Boolean {
        val url = raw.trim()
        return url.isEmpty() || url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }
}
