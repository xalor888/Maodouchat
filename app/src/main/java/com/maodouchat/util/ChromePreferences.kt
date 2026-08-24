package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local chrome toggles that are not a theme family.
 * Floating glass dock is ON by default for 默认 (maodou); TG chrome never uses it.
 */
object ChromePreferences {
    private const val PREFS = "general_settings"
    private const val KEY_FLOATING_DOCK = "floating_glass_dock"

    @Volatile
    private var seeded = false
    private val _floatingDock = MutableStateFlow(true)
    val floatingDock: StateFlow<Boolean> = _floatingDock.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY_FLOATING_DOCK) {
            _floatingDock.value = prefs.getBoolean(KEY_FLOATING_DOCK, true)
        }
    }

    fun ensureSeeded(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _floatingDock.value = prefs.getBoolean(KEY_FLOATING_DOCK, true)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            seeded = true
        }
    }

    fun isFloatingDockEnabled(context: Context): Boolean {
        ensureSeeded(context)
        return _floatingDock.value
    }

    fun setFloatingDockEnabled(context: Context, enabled: Boolean) {
        ensureSeeded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLOATING_DOCK, enabled)
            .apply()
        _floatingDock.value = enabled
    }
}
