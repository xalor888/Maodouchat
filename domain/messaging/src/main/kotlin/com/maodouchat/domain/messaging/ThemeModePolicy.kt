package com.maodouchat.domain.messaging

// 主题模式原始字符串 → 归一值；未知回落 system（原 ThemePreferences.normalize 逐字平移）。
object ThemeModePolicy {
    fun normalizeThemeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "light" -> "light"
        "dark" -> "dark"
        "scheduled" -> "scheduled"
        else -> "system"
    }
}
