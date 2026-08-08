package com.maodouchat.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocaleManager {
    const val MODE_SYSTEM = "system"
    const val MODE_CHINESE = "zh"
    const val MODE_ENGLISH = "en"

    private const val PREFS_NAME = "general_settings"
    private const val KEY_LANGUAGE = "language_mode"

    fun getMode(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) {
                return when (locales[0]?.language) {
                    "en" -> MODE_ENGLISH
                    "zh" -> MODE_CHINESE
                    else -> MODE_SYSTEM
                }
            }
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, MODE_SYSTEM)
            ?.takeIf { it in supportedModes }
            ?: MODE_SYSTEM
    }

    fun setMode(context: Context, mode: String) {
        val normalized = mode.takeIf { it in supportedModes } ?: MODE_SYSTEM
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tags = languageTag(normalized)
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
        } else {
            (context as? Activity)?.recreate()
        }
    }

    fun wrap(context: Context): Context {
        val tag = languageTag(getMode(context))
        if (tag.isEmpty()) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    private fun languageTag(mode: String): String = when (mode) {
        MODE_CHINESE -> "zh-CN"
        MODE_ENGLISH -> "en"
        else -> ""
    }

    private val supportedModes = setOf(MODE_SYSTEM, MODE_CHINESE, MODE_ENGLISH)
}
