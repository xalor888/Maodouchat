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

    @Volatile
    private var seeded = false
    private val _mode = MutableStateFlow("system")
    val mode: StateFlow<String> = _mode.asStateFlow()
    private val _family = MutableStateFlow("maodou")
    val family: StateFlow<String> = _family.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY_THEME) {
            _mode.value = normalize(prefs.getString(KEY_THEME, "system"))
        }
        if (key == null || key == KEY_THEME_STYLE) {
            _family.value = normalizeStyle(prefs.getString(KEY_THEME_STYLE, "maodou"))
        }
    }

    fun ensureSeeded(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _mode.value = normalize(prefs.getString(KEY_THEME, "system"))
            _family.value = normalizeStyle(prefs.getString(KEY_THEME_STYLE, "maodou"))
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
}
