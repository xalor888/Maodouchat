package com.maodouchat.ui.screen.settings

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import com.maodouchat.R
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.flow.update

internal fun GeneralSettingsViewModel.setLanguageMode(mode: String) {
    val context = getApplication<Application>()
    val normalized = when (mode.lowercase()) {
        AppLocaleManager.MODE_CHINESE -> AppLocaleManager.MODE_CHINESE
        AppLocaleManager.MODE_ENGLISH -> AppLocaleManager.MODE_ENGLISH
        else -> AppLocaleManager.MODE_SYSTEM
    }
    if (_uiState.value.languageMode == normalized) return
    prefsRevision++
    AppLocaleManager.setMode(context, normalized)
    _uiState.update { it.copy(languageMode = normalized) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.setLinkPreviewEnabled(enabled: Boolean) {
    if (_uiState.value.linkPreviewEnabled == enabled) return
    prefsRevision++
    val context = getApplication<Application>()
    com.maodouchat.util.LinkPreviewPreferences.setEnabled(context, enabled)
    if (!enabled) {
        com.maodouchat.util.LinkPreviewRepository.clear()
    }
    _uiState.update { it.copy(linkPreviewEnabled = enabled) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.setUnreadPriorityEnabled(enabled: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.UNREAD_PRIORITY)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.unread_priority_disabled)) }
        return
    }
    if (_uiState.value.unreadPriorityEnabled == enabled) return
    prefsRevision++
    com.maodouchat.util.UnreadPriorityPreferences.setEnabled(context, enabled)
    _uiState.update { it.copy(unreadPriorityEnabled = enabled) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.setChatWallpaper(presetId: String) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_WALLPAPER)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.chat_wallpaper_disabled)) }
        return
    }
    val preset = com.maodouchat.util.ChatAppearancePolicy.normalizeWallpaper(presetId)
    if (_uiState.value.chatWallpaper == preset.id) return
    prefsRevision++
    com.maodouchat.util.ChatAppearancePreferences.setWallpaper(context, preset)
    _uiState.update { it.copy(chatWallpaper = preset.id) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.setChatFontScale(scaleId: String) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_FONT_SCALE)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.chat_font_scale_disabled)) }
        return
    }
    val scale = com.maodouchat.util.ChatAppearancePolicy.normalizeFontScale(scaleId)
    if (_uiState.value.chatFontScale == scale.id) return
    prefsRevision++
    com.maodouchat.util.ChatAppearancePreferences.setFontScale(context, scale)
    _uiState.update { it.copy(chatFontScale = scale.id) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.setMediaAutoDownloadMode(mode: String) {
    val context = getApplication<Application>()
    val normalized = com.maodouchat.util.MediaAutoDownloadPreferences.normalizeForWrite(mode)
    if (_uiState.value.mediaAutoDownloadMode == normalized) return
    prefsRevision++
    com.maodouchat.util.MediaAutoDownloadPreferences.setMode(context, normalized)
    _uiState.update { it.copy(mediaAutoDownloadMode = normalized) }
    pushClientPrefs()
}
