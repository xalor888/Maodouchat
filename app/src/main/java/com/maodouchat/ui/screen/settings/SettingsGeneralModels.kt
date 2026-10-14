package com.maodouchat.ui.screen.settings

import com.maodouchat.util.AppLocaleManager

/** 「通用」设置页的 UI 状态。 */
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
