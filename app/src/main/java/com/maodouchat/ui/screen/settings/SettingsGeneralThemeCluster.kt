package com.maodouchat.ui.screen.settings

import androidx.core.content.edit
import android.app.Application
import kotlinx.coroutines.flow.update

internal fun GeneralSettingsViewModel.setThemeMode(mode: String) {
    val context = getApplication<Application>()
    val normalized = com.maodouchat.util.ThemePreferences.normalize(mode)
    if (_uiState.value.themeMode == normalized) return
    prefsRevision++
    com.maodouchat.util.ThemePreferences.setMode(context, normalized)
    prefs.edit { putString(KEY_THEME, normalized) }
    _uiState.update { it.copy(themeMode = normalized) }
    pushClientPrefs()
}

/** 主题风格家族（含 TG 1:1 还原主题），随客户端偏好云同步。 */
internal fun GeneralSettingsViewModel.setThemeStyle(style: String) {
    val context = getApplication<Application>()
    val normalized = com.maodouchat.util.ThemePreferences.normalizeStyle(style)
    if (_uiState.value.themeStyle == normalized) return
    prefsRevision++
    com.maodouchat.util.ThemePreferences.setStyle(context, normalized)
    _uiState.update { it.copy(themeStyle = normalized) }
    pushClientPrefs()
}

/** 自定义强调色（TG 式），随客户端偏好云同步。 */
internal fun GeneralSettingsViewModel.setAccentColor(accentId: String) {
    val context = getApplication<Application>()
    val normalized = com.maodouchat.util.ThemePreferences.normalizeAccent(accentId)
    if (_uiState.value.accentColor == normalized) return
    prefsRevision++
    com.maodouchat.util.ThemePreferences.setAccent(context, normalized)
    _uiState.update { it.copy(accentColor = normalized) }
    pushClientPrefs()
}

internal fun GeneralSettingsViewModel.text(id: Int): String = getApplication<Application>().getString(id)

internal fun GeneralSettingsViewModel.isCurrentOwner(expectedUserId: String): Boolean =
    com.maodouchat.security.BackgroundSessionGate.mayContinue(
        expectedUserId = expectedUserId,
    )
