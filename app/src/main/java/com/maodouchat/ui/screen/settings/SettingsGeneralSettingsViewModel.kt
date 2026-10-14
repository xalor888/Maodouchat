package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class GeneralSettingsViewModel(application: Application) : AndroidViewModel(application) {
    internal val prefs = application.getSharedPreferences("general_settings", Application.MODE_PRIVATE)
    internal val clientPrefsPushMutex = Mutex()
    internal var prefsRevision = 0L
    internal var clientPrefsPullGeneration = 0L
    internal var clientPrefsPullJob: kotlinx.coroutines.Job? = null
    internal var clientPrefsPushGeneration = 0L
    internal var cacheRefreshGeneration = 0L
    internal var cacheRefreshJob: kotlinx.coroutines.Job? = null
    internal var clearCacheJob: kotlinx.coroutines.Job? = null

    internal val _uiState = MutableStateFlow(
        GeneralSettingsUiState(
            themeMode = prefs.getString(KEY_THEME, "system") ?: "system",
            themeStyle = com.maodouchat.util.ThemePreferences.getStyle(application),
            accentColor = com.maodouchat.util.ThemePreferences.getAccent(application),
            languageMode = AppLocaleManager.getMode(application),
            linkPreviewEnabled = com.maodouchat.util.LinkPreviewPreferences.isEnabled(application),
            unreadPriorityEnabled = com.maodouchat.util.UnreadPriorityPreferences.isEnabled(application),
            mediaAutoDownloadMode = com.maodouchat.util.MediaAutoDownloadPreferences.getMode(application),
            chatWallpaper = com.maodouchat.util.ChatAppearancePreferences.getWallpaper(application).id,
            chatFontScale = com.maodouchat.util.ChatAppearancePreferences.getFontScale(application).id
        )
    )
    val uiState: StateFlow<GeneralSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshCacheSize()
        pullClientPrefsFromCloud()
    }

    companion object {
        internal const val KEY_THEME = "theme_mode"
    }
}
