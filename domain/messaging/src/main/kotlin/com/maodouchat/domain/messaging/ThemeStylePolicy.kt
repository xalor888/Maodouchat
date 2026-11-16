package com.maodouchat.domain.messaging

// 主题风格归一：maodou 默认，tg_* 保留对应 Telegram 扁平主题，未知回落 maodou。
object ThemeStylePolicy {
    fun normalizeThemeStyle(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "tg_classic", "tg", "telegram", "telegram_classic" -> "tg_classic"
        "tg_midnight", "midnight" -> "tg_midnight"
        "tg_graphite", "graphite" -> "tg_graphite"
        else -> "maodou"
    }
}
