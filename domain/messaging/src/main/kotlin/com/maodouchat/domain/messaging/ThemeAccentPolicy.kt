package com.maodouchat.domain.messaging

// 强调色 id（none 或 ACCENT_OPTIONS 中的颜色），未知回落 none。
object ThemeAccentPolicy {
    fun normalizeThemeAccent(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id == "none" || id == "blue" || id == "green" || id == "purple" || id == "orange" || id == "pink" || id == "red" || id == "teal") id else "none"
    }
}
