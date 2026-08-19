package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 玻璃悬浮底栏开关（general_settings）。
 * 开启后主界面底栏以半透明圆角悬浮卡片呈现；关闭则使用传统贴底样式。
 * 与 ThemePreferences 相同模式：进程级 StateFlow，设置页改动即时反映。
 */
object GlassBottomBarPreferences {
    private const val PREFS = "general_settings"
    private const val KEY = "glass_bottom_bar"
    private const val DEFAULT = true

    @Volatile
    private var seeded = false
    private val _enabled = MutableStateFlow(DEFAULT)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY) {
            _enabled.value = prefs.getBoolean(KEY, DEFAULT)
        }
    }

    fun ensureSeeded(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY, DEFAULT)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            seeded = true
        }
    }

    fun isEnabled(context: Context): Boolean {
        ensureSeeded(context)
        return _enabled.value
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
        _enabled.value = enabled
    }
}
