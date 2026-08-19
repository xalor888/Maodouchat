package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Theme mode (system/light/dark) backed by general_settings prefs.
 * Exposes a process-wide StateFlow so Compose can react to cloud pull / settings
 * without requiring a full process restart.
 */
object ThemePreferences {
    private const val PREFS = "general_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_THEME_STYLE = "theme_style"
    private const val KEY_ACCENT = "accent_color"
    // 9.211：定时深色（TG 式）——本地分钟数时段，默认 21:00 → 07:00
    private const val KEY_NIGHT_START = "night_start_minutes"
    private const val KEY_NIGHT_END = "night_end_minutes"
    const val NIGHT_START_DEFAULT = 21 * 60
    const val NIGHT_END_DEFAULT = 7 * 60

    @Volatile
    private var seeded = false
    private val _mode = MutableStateFlow("system")
    val mode: StateFlow<String> = _mode.asStateFlow()
    private val _family = MutableStateFlow("maodou")
    val family: StateFlow<String> = _family.asStateFlow()
    private val _accent = MutableStateFlow("none")
    val accent: StateFlow<String> = _accent.asStateFlow()
    private val _nightStart = MutableStateFlow(NIGHT_START_DEFAULT)
    val nightStart: StateFlow<Int> = _nightStart.asStateFlow()
    private val _nightEnd = MutableStateFlow(NIGHT_END_DEFAULT)
    val nightEnd: StateFlow<Int> = _nightEnd.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY_THEME) {
            _mode.value = normalize(prefs.getString(KEY_THEME, "system"))
        }
        if (key == null || key == KEY_THEME_STYLE) {
            _family.value = normalizeStyle(prefs.getString(KEY_THEME_STYLE, "maodou"))
        }
        if (key == null || key == KEY_ACCENT) {
            _accent.value = normalizeAccent(prefs.getString(KEY_ACCENT, "none"))
        }
        if (key == null || key == KEY_NIGHT_START) {
            _nightStart.value = prefs.getInt(KEY_NIGHT_START, NIGHT_START_DEFAULT).coerceIn(0, 23 * 60 + 59)
        }
        if (key == null || key == KEY_NIGHT_END) {
            _nightEnd.value = prefs.getInt(KEY_NIGHT_END, NIGHT_END_DEFAULT).coerceIn(0, 23 * 60 + 59)
        }
    }

    fun ensureSeeded(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _mode.value = normalize(prefs.getString(KEY_THEME, "system"))
            _family.value = normalizeStyle(prefs.getString(KEY_THEME_STYLE, "maodou"))
            _accent.value = normalizeAccent(prefs.getString(KEY_ACCENT, "none"))
            _nightStart.value = prefs.getInt(KEY_NIGHT_START, NIGHT_START_DEFAULT).coerceIn(0, 23 * 60 + 59)
            _nightEnd.value = prefs.getInt(KEY_NIGHT_END, NIGHT_END_DEFAULT).coerceIn(0, 23 * 60 + 59)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            seeded = true
        }
    }

    fun getMode(context: Context): String {
        ensureSeeded(context)
        return _mode.value
    }

    fun setMode(context: Context, mode: String) {
        val normalized = normalize(mode)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, normalized)
            .apply()
        // Listener also updates; set immediately for same-thread readers
        _mode.value = normalized
    }

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "light" -> "light"
        "dark" -> "dark"
        "scheduled" -> "scheduled"
        else -> "system"
    }

    fun getStyle(context: Context): String {
        ensureSeeded(context)
        return _family.value
    }

    fun setStyle(context: Context, style: String) {
        val normalized = normalizeStyle(style)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_STYLE, normalized)
            .apply()
        _family.value = normalized
    }

    /** 主题风格家族 id（maodou / tg_classic / tg_midnight / tg_graphite）。 */
    fun normalizeStyle(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id in setOf("maodou", "tg_classic", "tg_midnight", "tg_graphite")) id else "maodou"
    }

    fun getAccent(context: Context): String {
        ensureSeeded(context)
        return _accent.value
    }

    fun setAccent(context: Context, accentId: String) {
        val normalized = normalizeAccent(accentId)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT, normalized)
            .apply()
        _accent.value = normalized
    }

    /** 强调色 id（none 或 ACCENT_OPTIONS 中的颜色）。 */
    fun normalizeAccent(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id == "none" || id == "blue" || id == "green" || id == "purple" || id == "orange" || id == "pink" || id == "red" || id == "teal") id else "none"
    }

    fun setNightWindow(context: Context, startMinutes: Int, endMinutes: Int) {
        ensureSeeded(context)
        val s = startMinutes.coerceIn(0, 23 * 60 + 59)
        val e = endMinutes.coerceIn(0, 23 * 60 + 59)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NIGHT_START, s)
            .putInt(KEY_NIGHT_END, e)
            .apply()
        _nightStart.value = s
        _nightEnd.value = e
    }

    /** 当前分钟数是否落在夜间窗口内（支持跨午夜窗口，如 21:00→07:00）。 */
    fun isWithinNightWindow(currentMinute: Int, start: Int, end: Int): Boolean =
        if (start <= end) currentMinute in start until end
        else currentMinute >= start || currentMinute < end
}
