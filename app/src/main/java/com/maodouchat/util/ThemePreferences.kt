package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.maodouchat.domain.messaging.NightWindowPolicy
import com.maodouchat.domain.messaging.ThemeAccentPolicy
import com.maodouchat.domain.messaging.ThemeModePolicy
import com.maodouchat.domain.messaging.ThemeStylePolicy
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
    // 品牌默认 = MAODOU 白底液态玻璃（ThemeStyle.kt 的注释与 PICKABLE 均以它为唯一可选家族；
    // 此前误设 tg_classic，导致液态玻璃 / 悬浮 dock / 玻璃底栏在全新安装上全部默认失效）
    private const val DEFAULT_THEME_STYLE = "maodou"
    private const val KEY_ACCENT = "accent_color"
    // 9.211：定时深色（TG 式）——本地分钟数时段，默认 21:00 → 07:00
    private const val KEY_NIGHT_START = "night_start_minutes"
    private const val KEY_NIGHT_END = "night_end_minutes"
    // 9.258：OLED 纯黑深色（TG Amoled Black 式）——深色模式下背景纯黑省电
    private const val KEY_OLED_BLACK = "oled_black"
    const val NIGHT_START_DEFAULT = 21 * 60
    const val NIGHT_END_DEFAULT = 7 * 60

    @Volatile
    private var seeded = false
    private val _mode = MutableStateFlow("system")
    val mode: StateFlow<String> = _mode.asStateFlow()
    private val _family = MutableStateFlow(DEFAULT_THEME_STYLE)
    val family: StateFlow<String> = _family.asStateFlow()
    private val _accent = MutableStateFlow("none")
    val accent: StateFlow<String> = _accent.asStateFlow()
    private val _nightStart = MutableStateFlow(NIGHT_START_DEFAULT)
    val nightStart: StateFlow<Int> = _nightStart.asStateFlow()
    private val _nightEnd = MutableStateFlow(NIGHT_END_DEFAULT)
    val nightEnd: StateFlow<Int> = _nightEnd.asStateFlow()
    private val _oledBlack = MutableStateFlow(false)
    val oledBlack: StateFlow<Boolean> = _oledBlack.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY_THEME) {
            _mode.value = ThemeModePolicy.normalizeThemeMode(prefs.getString(KEY_THEME, "system"))
        }
        if (key == null || key == KEY_THEME_STYLE) {
            _family.value = ThemeStylePolicy.normalizeThemeStyle(prefs.getString(KEY_THEME_STYLE, DEFAULT_THEME_STYLE))
        }
        if (key == null || key == KEY_ACCENT) {
            _accent.value = ThemeAccentPolicy.normalizeThemeAccent(prefs.getString(KEY_ACCENT, "none"))
        }
        if (key == null || key == KEY_NIGHT_START) {
            _nightStart.value = prefs.getInt(KEY_NIGHT_START, NIGHT_START_DEFAULT).coerceIn(0, 23 * 60 + 59)
        }
        if (key == null || key == KEY_NIGHT_END) {
            _nightEnd.value = prefs.getInt(KEY_NIGHT_END, NIGHT_END_DEFAULT).coerceIn(0, 23 * 60 + 59)
        }
        if (key == null || key == KEY_OLED_BLACK) {
            _oledBlack.value = prefs.getBoolean(KEY_OLED_BLACK, false)
        }
    }

    fun ensureSeeded(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _mode.value = ThemeModePolicy.normalizeThemeMode(prefs.getString(KEY_THEME, "system"))
            val storedStyle = prefs.getString(KEY_THEME_STYLE, DEFAULT_THEME_STYLE)
            val normalizedStyle = ThemeStylePolicy.normalizeThemeStyle(storedStyle)
            if (storedStyle != normalizedStyle) {
                prefs.edit { putString(KEY_THEME_STYLE, normalizedStyle) }
            }
            _family.value = normalizedStyle
            _accent.value = ThemeAccentPolicy.normalizeThemeAccent(prefs.getString(KEY_ACCENT, "none"))
            _nightStart.value = prefs.getInt(KEY_NIGHT_START, NIGHT_START_DEFAULT).coerceIn(0, 23 * 60 + 59)
            _nightEnd.value = prefs.getInt(KEY_NIGHT_END, NIGHT_END_DEFAULT).coerceIn(0, 23 * 60 + 59)
            _oledBlack.value = prefs.getBoolean(KEY_OLED_BLACK, false)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            seeded = true
        }
    }

    fun getMode(context: Context): String {
        ensureSeeded(context)
        return _mode.value
    }

    fun setMode(context: Context, mode: String) {
        val normalized = ThemeModePolicy.normalizeThemeMode(mode)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
            putString(KEY_THEME, normalized)
        }
        // Listener also updates; set immediately for same-thread readers
        _mode.value = normalized
    }

    fun getStyle(context: Context): String {
        ensureSeeded(context)
        return _family.value
    }

    fun setStyle(context: Context, style: String) {
        val normalized = ThemeStylePolicy.normalizeThemeStyle(style)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
            putString(KEY_THEME_STYLE, normalized)
        }
        _family.value = normalized
    }

    fun getAccent(context: Context): String {
        ensureSeeded(context)
        return _accent.value
    }

    fun setAccent(context: Context, accentId: String) {
        val normalized = ThemeAccentPolicy.normalizeThemeAccent(accentId)
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
            putString(KEY_ACCENT, normalized)
        }
        _accent.value = normalized
    }

    fun setNightWindow(context: Context, startMinutes: Int, endMinutes: Int) {
        ensureSeeded(context)
        val s = startMinutes.coerceIn(0, 23 * 60 + 59)
        val e = endMinutes.coerceIn(0, 23 * 60 + 59)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
            putInt(KEY_NIGHT_START, s)
            putInt(KEY_NIGHT_END, e)
        }
        _nightStart.value = s
        _nightEnd.value = e
    }

    /** 9.258：OLED 纯黑开关（深色模式下生效）。 */
    fun setOledBlack(context: Context, enabled: Boolean) {
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
            putBoolean(KEY_OLED_BLACK, enabled)
        }
        _oledBlack.value = enabled
    }
}
