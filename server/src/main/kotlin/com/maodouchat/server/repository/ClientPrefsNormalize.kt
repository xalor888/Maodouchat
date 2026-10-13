package com.maodouchat.server.repository

/** 客户端偏好归一化：写入侧把任意输入收敛到允许值域。 */
internal fun ClientPrefsRepository.normalizeTheme(raw: String): String = when (raw.trim().lowercase()) {
    "light" -> "light"
    "dark" -> "dark"
    // 9.211：定时深色模式透传（具体时段为设备本地设置，不随云端同步）
    "scheduled" -> "scheduled"
    else -> "system"
}

internal fun ClientPrefsRepository.normalizeThemeStyle(@Suppress("UNUSED_PARAMETER") raw: String): String = "maodou"

internal fun ClientPrefsRepository.normalizeAccentColor(raw: String): String {
    val id = raw.trim().lowercase().take(16)
    return if (id in ALLOWED_ACCENT_COLORS) id else "none"
}

internal fun ClientPrefsRepository.normalizeLanguage(raw: String): String = when (raw.trim().lowercase()) {
    "zh", "zh-cn", "chinese" -> "zh"
    "en", "english" -> "en"
    else -> "system"
}

internal fun ClientPrefsRepository.normalizeWallpaper(raw: String): String {
    val id = raw.trim().lowercase().take(32)
    return if (id in ALLOWED_WALLPAPERS) id else "default"
}

internal fun ClientPrefsRepository.normalizeFont(raw: String): String {
    val id = raw.trim().lowercase().take(16)
    return if (id in ALLOWED_FONTS) id else "normal"
}

internal fun ClientPrefsRepository.normalizeWritingPreset(raw: String): String {
    val id = raw.trim().lowercase().take(40)
    return if (id in ALLOWED_WRITING_PRESETS) id else "none"
}

internal fun ClientPrefsRepository.normalizeWritingCustom(raw: String): String =
    raw.trim().replace(WHITESPACE_REGEX, " ").take(320)

internal fun ClientPrefsRepository.normalizeAppLockTimeout(raw: Long): Long =
    if (raw in ALLOWED_APP_LOCK_TIMEOUTS) raw else 5L

private val WHITESPACE_REGEX = Regex("\\s+")
private val ALLOWED_WALLPAPERS = setOf(
    "default", "mint", "lavender", "sand", "night", "rose", "sky", "slate", "peach", "olive", "coral", "plum",
    "indigo", "amber", "teal", "graphite"
)
private val ALLOWED_FONTS = setOf("small", "normal", "large", "xlarge", "xxlarge")
private val ALLOWED_ACCENT_COLORS = setOf("none", "blue", "green", "purple", "orange", "pink", "red", "teal")
private val ALLOWED_WRITING_PRESETS = setOf(
    "none", "concise", "formal", "warm", "professional", "casual", "witty", "empathetic",
    "direct", "enthusiastic"
)
private val ALLOWED_APP_LOCK_TIMEOUTS = setOf(1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L)

