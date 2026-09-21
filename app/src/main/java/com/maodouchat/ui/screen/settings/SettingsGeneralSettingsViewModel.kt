package com.maodouchat.ui.screen.settings

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.network.ApiService
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「通用」设置页的 ViewModel（G121 从 `SettingsSubViewModels.kt` 拆出，原 493 行）。
 *
 * 管主题/外观/语言/壁纸/字号、缓存清理、关于，以及 `SettingsViewModel.changePassword`
 * （修改密码：旧密码校验、长度、二次确认、服务端成功后清会话）。
 * 含它的 UI 状态数据类 `GeneralSettingsUiState`。
 *
 * **拆解约束**：不直接抓应用级数据库单例；网络经 `ApiService`，凭据经 `TokenManager`，
 * 偏好经 `AppLocaleManager` / `MediaCache`。纯搬移，不改判断。
 */

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
    private val prefs = application.getSharedPreferences("general_settings", Application.MODE_PRIVATE)
    private val tokenManager = TokenManager.getInstance(application)
    private val clientPrefsPushMutex = Mutex()
    private var prefsRevision = 0L
    private var clientPrefsPullGeneration = 0L
    private var clientPrefsPullJob: kotlinx.coroutines.Job? = null
    private var clientPrefsPushGeneration = 0L
    private var cacheRefreshGeneration = 0L
    private var cacheRefreshJob: kotlinx.coroutines.Job? = null
    private var clearCacheJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
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

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    init {
        refreshCacheSize()
        pullClientPrefsFromCloud()
    }

    fun setThemeMode(mode: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalize(mode)
        if (_uiState.value.themeMode == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setMode(context, normalized)
        prefs.edit().putString(KEY_THEME, normalized).apply()
        _uiState.update { it.copy(themeMode = normalized) }
        pushClientPrefs()
    }

    /** 主题风格家族（含 TG 1:1 还原主题），随客户端偏好云同步。 */
    fun setThemeStyle(style: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalizeStyle(style)
        if (_uiState.value.themeStyle == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setStyle(context, normalized)
        _uiState.update { it.copy(themeStyle = normalized) }
        pushClientPrefs()
    }

    /** 自定义强调色（TG 式），随客户端偏好云同步。 */
    fun setAccentColor(accentId: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalizeAccent(accentId)
        if (_uiState.value.accentColor == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setAccent(context, normalized)
        _uiState.update { it.copy(accentColor = normalized) }
        pushClientPrefs()
    }

    fun setLanguageMode(mode: String) {
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

    fun setLinkPreviewEnabled(enabled: Boolean) {
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

    fun setUnreadPriorityEnabled(enabled: Boolean) {
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

    fun setChatWallpaper(presetId: String) {
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

    fun setChatFontScale(scaleId: String) {
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

    fun setMediaAutoDownloadMode(mode: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.MediaAutoDownloadPreferences.normalizeForWrite(mode)
        if (_uiState.value.mediaAutoDownloadMode == normalized) return
        prefsRevision++
        com.maodouchat.util.MediaAutoDownloadPreferences.setMode(context, normalized)
        _uiState.update { it.copy(mediaAutoDownloadMode = normalized) }
        pushClientPrefs()
    }

    private fun pullClientPrefsFromCloud() {
        val generation = ++clientPrefsPullGeneration
        clientPrefsPullJob?.cancel()
        val revisionAtStart = prefsRevision
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.getClientPrefs(liveToken).onSuccess { remote ->
                    if (
                        generation == clientPrefsPullGeneration &&
                        prefsRevision == revisionAtStart &&
                        isCurrentOwner(ownerUserId)
                    ) {
                        applyRemotePrefs(remote)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == clientPrefsPullGeneration && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
        clientPrefsPullJob = job
        job.invokeOnCompletion {
            if (clientPrefsPullJob === job) clientPrefsPullJob = null
        }
    }

    private fun applyRemotePrefs(remote: com.maodouchat.network.ClientPrefsDto) {
        val context = getApplication<Application>()
        com.maodouchat.util.ClientPrefsSync.apply(context, remote)
        val theme = com.maodouchat.util.ThemePreferences.normalize(remote.themeMode)
        // 9.204：主题风格云端拉取——写入本地偏好（ThemePreferences 的 StateFlow 驱动全局重组）
        val themeStyle = com.maodouchat.util.ThemePreferences.normalizeStyle(remote.themeStyle)
        com.maodouchat.util.ThemePreferences.setStyle(context, themeStyle)
        val accentColor = com.maodouchat.util.ThemePreferences.normalizeAccent(remote.accentColor)
        com.maodouchat.util.ThemePreferences.setAccent(context, accentColor)
        val language = when (remote.languageMode.lowercase()) {
            AppLocaleManager.MODE_CHINESE, "zh-cn", "chinese" -> AppLocaleManager.MODE_CHINESE
            AppLocaleManager.MODE_ENGLISH, "english" -> AppLocaleManager.MODE_ENGLISH
            else -> AppLocaleManager.MODE_SYSTEM
        }
        val wallpaper = com.maodouchat.util.ChatAppearancePolicy.normalizeWallpaper(remote.chatWallpaper)
        val font = com.maodouchat.util.ChatAppearancePolicy.normalizeFontScale(remote.chatFontScale)
        _uiState.update {
            it.copy(
                themeMode = theme,
                themeStyle = themeStyle,
                accentColor = accentColor,
                languageMode = language,
                linkPreviewEnabled = remote.linkPreviewEnabled,
                unreadPriorityEnabled = remote.unreadPriorityEnabled,
                chatWallpaper = wallpaper.id,
                chatFontScale = font.id
            )
        }
    }

    private fun pushClientPrefs() {
        val generation = ++clientPrefsPushGeneration
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            try {
                clientPrefsPushMutex.withLock {
                    if (generation != clientPrefsPushGeneration || !isCurrentOwner(ownerUserId)) {
                        return@withLock
                    }
                    val state = _uiState.value
                    val request = ClientPrefsUpdateRequest(
                        themeMode = state.themeMode,
                        themeStyle = state.themeStyle,
                        accentColor = state.accentColor,
                        languageMode = state.languageMode,
                        chatWallpaper = state.chatWallpaper,
                        chatFontScale = state.chatFontScale,
                        linkPreviewEnabled = state.linkPreviewEnabled,
                        unreadPriorityEnabled = state.unreadPriorityEnabled
                    )
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ApiService.putClientPrefs(liveToken, request).onFailure { error ->
                        if (generation == clientPrefsPushGeneration && isCurrentOwner(ownerUserId)) {
                            _uiState.update {
                                it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                            }
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == clientPrefsPushGeneration && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        if (clearCacheJob?.isActive == true) return
        cacheRefreshGeneration++
        cacheRefreshJob?.cancel()
        val generation = cacheRefreshGeneration
        val job = viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val removedBytes = withContext(Dispatchers.IO) {
                    val before = MediaCache.currentCacheBytes(context)
                    AttachmentTransferCoordinator.deleteAll(context)
                    MediaCache.cleanupReturningBytes(context)
                    com.maodouchat.util.LinkPreviewRepository.clear()
                    // 1.35：清除 Coil 图片磁盘缓存（内存缓存在低内存时已清，这里补磁盘）
                    runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
                    (before - MediaCache.currentCacheBytes(context)).coerceAtLeast(0L)
                }
                if (cacheRefreshGeneration != generation) return@launch
                val text = formatSize(removedBytes)
                _uiState.update {
                    it.copy(
                        cacheSizeText = text,
                        infoMessage = context.getString(R.string.general_cache_cleared, text),
                    )
                }
                refreshCacheSize()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (cacheRefreshGeneration == generation) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
        clearCacheJob = job
        job.invokeOnCompletion {
            if (clearCacheJob === job) clearCacheJob = null
        }
    }

    fun showComingSoon() { _uiState.update { it.copy(infoMessage = getApplication<Application>().getString(R.string.general_about_summary)) } }

    fun consumeInfoMessage() {
        if (_uiState.value.infoMessage != null) {
            _uiState.update { it.copy(infoMessage = null) }
        }
    }

    private fun refreshCacheSize() {
        val generation = ++cacheRefreshGeneration
        cacheRefreshJob?.cancel()
        val job = viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val bytes = withContext(Dispatchers.IO) { MediaCache.currentCacheBytes(context) }
                if (cacheRefreshGeneration == generation) {
                    _uiState.update { it.copy(cacheSizeText = formatSize(bytes)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (cacheRefreshGeneration == generation) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
        cacheRefreshJob = job
        job.invokeOnCompletion {
            if (cacheRefreshJob === job) cacheRefreshJob = null
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024L * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024 * 1024) return "${bytes / (1024L * 1024)} MB"
        return "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}

/**
 * 改密码互斥锁（G121 从 `SettingsSubViewModels.kt` 删档时按 git 记录恢复）。
 * 只服务 `SettingsViewModel.changePassword`——防止重复提交。
 */
private val passwordChangeMutex = Mutex()

fun SettingsViewModel.changePassword(old: String, new: String, confirm: String, onSuccess: () -> Unit) {
    if (!passwordChangeMutex.tryLock()) return
    if (old.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_enter_old_password)) }
        return
    }
    if (new.length < 6) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_new_password_length)) }
        return
    }
    if (new != confirm) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_password_mismatch)) }
        return
    }
    val ownerUserId = tokenManager.getUserId().orEmpty()
    val token = tokenManager.getToken()
    if (token.isNullOrBlank() || ownerUserId.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
        return
    }
    while (true) {
        val state = _uiState.value
        if (state.isSaving) {
            passwordChangeMutex.unlock()
            return
        }
        if (_uiState.compareAndSet(state, state.copy(isSaving = true, errorMessage = null))) break
    }
    val job = viewModelScope.launch {
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                // 8.38：门禁失败需复位 isSaving，否则弹窗转圈且无法关闭
                _uiState.update { it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.changePassword(liveToken, old, new).fold(
                onSuccess = {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        // 8.38：成功路径二次门禁失败也需复位，否则弹窗无法关闭
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    // 服务端已 revoke 全部 token + 断 WS；本地必须立刻清会话，
                    // 否则下一次 401→refresh 失败会走 tokenExpired 并 destroyEncryptedDatabase。
                    val purged = withContext(kotlinx.coroutines.NonCancellable) {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@withContext false
                        }
                        // 9.140：带账号归属校验 purge——此前无 expectedOwnerUserId，
                        // 断连窗口内换号会把新账号的会话一并清掉
                        (getApplication() as com.maodouchat.MaodouchatApp).secureSessionManager
                            .purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                                ),
                                expectedOwnerUserId = ownerUserId
                            )
                    }
                    if (!purged) {
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isLoggedOut = true,
                            successMessage = text(R.string.settings_password_changed)
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: text(R.string.settings_password_change_failed)) }
                    }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { it.copy(isSaving = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: text(R.string.settings_password_change_failed),
                    )
                }
            }
        }
    }
    job.invokeOnCompletion { passwordChangeMutex.unlock() }
}
