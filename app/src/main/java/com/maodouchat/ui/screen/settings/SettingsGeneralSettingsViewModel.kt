package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** 「通用」设置页的 ViewModel：主题/外观/语言/壁纸/字号、缓存清理与客户端偏好云同步。 */
data class GeneralSettingsUiState(
    val themeMode: String = "system", // system / light / dark
    val themeStyle: String = "maodou", // 仅 maodou；旧 tg_* 读入归一
    val accentColor: String = "none", // none / blue / green / purple / orange / pink / red / teal
    val languageMode: String = AppLocaleManager.MODE_SYSTEM,
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val mediaAutoDownloadMode: String = com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY,
    val chatWallpaper: String = com.maodouchat.util.ChatWallpaperPreset.DEFAULT.id,
    val chatFontScale: String = com.maodouchat.util.ChatFontScale.NORMAL.id,
    val cacheSizeText: String = "0 KB",
    val infoMessage: String? = null
)

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
