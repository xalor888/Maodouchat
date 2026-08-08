package com.maodouchat.util

import android.content.Context
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.network.ApiService
import com.maodouchat.network.ClientPrefsDto
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.SensitiveActionGate

/**
 * Applies the multi-device non-secret client-prefs blob to local stores.
 * Safe to call after login / cold start when a session exists.
 */
object ClientPrefsSync {

    suspend fun pullAndApply(context: Context): ClientPrefsDto? {
        val app = context.applicationContext
        val tokenManager = TokenManager.getInstance(app)
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return null
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) return null
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val result = ApiService.getClientPrefs(liveToken)
        val error = result.exceptionOrNull()
        if (error is kotlinx.coroutines.CancellationException) throw error
        val remote = result.getOrNull() ?: return null
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) return null
        apply(app, remote)
        return remote
    }

    fun apply(context: Context, remote: ClientPrefsDto) {
        val app = context.applicationContext
        val theme = ThemePreferences.normalize(remote.themeMode)
        ThemePreferences.setMode(app, theme)
        app.getSharedPreferences("general_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", theme)
            .apply()

        val language = when (remote.languageMode.lowercase()) {
            AppLocaleManager.MODE_CHINESE, "zh-cn", "chinese" -> AppLocaleManager.MODE_CHINESE
            AppLocaleManager.MODE_ENGLISH, "english" -> AppLocaleManager.MODE_ENGLISH
            else -> AppLocaleManager.MODE_SYSTEM
        }
        if (AppLocaleManager.getMode(app) != language) {
            AppLocaleManager.setMode(app, language)
        }

        val wallpaper = ChatAppearancePolicy.normalizeWallpaper(remote.chatWallpaper)
        val font = ChatAppearancePolicy.normalizeFontScale(remote.chatFontScale)
        ChatAppearancePreferences.setWallpaper(app, wallpaper)
        ChatAppearancePreferences.setFontScale(app, font)
        LinkPreviewPreferences.setEnabled(app, remote.linkPreviewEnabled)
        if (!remote.linkPreviewEnabled) {
            LinkPreviewRepository.clear()
        }
        UnreadPriorityPreferences.setEnabled(app, remote.unreadPriorityEnabled)

        AiWritingStylePreferences.save(
            app,
            enabled = remote.writingStyleEnabled,
            presetId = remote.writingStylePreset,
            customNote = remote.writingStyleCustom
        )

        val lockTimeout = when (remote.appLockTimeoutMinutes) {
            1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L -> remote.appLockTimeoutMinutes
            else -> 5L
        }
        if (AppLockManager.getTimeoutMinutes(app) != lockTimeout) {
            AppLockManager.setTimeoutMinutes(app, lockTimeout)
        }
        if (ScreenSecureManager.isEnabled(app) != remote.screenSecureEnabled) {
            ScreenSecureManager.setEnabled(app, remote.screenSecureEnabled)
        }
        if (SensitiveActionGate.isEnabled(app) != remote.sensitiveGateEnabled) {
            SensitiveActionGate.setEnabled(app, remote.sensitiveGateEnabled)
        }
    }
}
