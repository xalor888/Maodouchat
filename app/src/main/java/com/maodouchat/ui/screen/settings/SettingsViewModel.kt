package com.maodouchat.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maodouchat.explore.policy.ExploreDraftPolicy
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.AndroidSettingsRepository
import com.maodouchat.settings.model.SettingsUiState

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val settingsRepository: SettingsRepository = AndroidSettingsRepository(application),
    private val securityCoordinator: SecurityCoordinator = SecurityCoordinator(settingsRepository),
) : AndroidViewModel(application) {
    /** G328c：账号/设备/拉黑这类**命令式**端点走 data 层仓库（ui 不再直接调 ApiService）。 */
    private val accountApi get() = com.maodouchat.data.repository.AccountSecurityNetworkRepository()

    private var clientPrefsPullJob: Job? = null
    private val clientPrefsPushMutex = Mutex()

    internal fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    internal val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val visibilityOptions = listOf(
        "PUBLIC" to text(R.string.explore_visibility_public),
        "CONTACTS" to text(R.string.explore_visibility_contacts),
        "PRIVATE" to text(R.string.explore_visibility_private)
    )

    private val privacyController by lazy {
        SettingsPrivacyController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
            settingsRepository = settingsRepository,
        )
    }

    init {
        loadUserInfo()
        privacyController.loadPrivacy()
    }

    // G179：回落 PUBLIC（ExploreDraftPolicy 回落 PRIVATE，分歧待决策，详见 SettingsVisibilityPolicy.kt）
    // G360：隐私设置一族抽到 SettingsPrivacyController（纯搬移不改判断）。
    fun openPrivacy() = privacyController.openPrivacy()

    fun closePrivacy() = privacyController.closePrivacy()

    fun onShowOnlineChange(v: Boolean) = privacyController.onShowOnlineChange(v)

    fun onOnlineVisibilityChange(v: String) = privacyController.onOnlineVisibilityChange(v)

    fun onShowStatusChange(v: Boolean) = privacyController.onShowStatusChange(v)

    fun onSearchableChange(v: Boolean) = privacyController.onSearchableChange(v)

    fun onDefaultVisibilityChange(v: String) = privacyController.onDefaultVisibilityChange(v)

    fun savePrivacy() = privacyController.savePrivacy()


    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )

    private fun loadUserInfo() {
        viewModelScope.launch {
            val session = settingsRepository.currentSession()
            if (session == null) {
                val defaultUser = text(R.string.settings_default_user)
                _uiState.update {
                    it.copy(
                        userName = defaultUser,
                        editName = defaultUser,
                        errorMessage = text(R.string.error_session_expired),
                    )
                }
                return@launch
            }
            settingsRepository.loadProfile(session).fold(
                onSuccess = { profile ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    _uiState.update {
                        it.copy(
                            userName = profile.name,
                            userId = profile.id,
                            userAvatar = profile.avatar,
                            userStatus = profile.status,
                            editStatus = profile.status,
                            isModerator = profile.isModerator,
                            editName = profile.name,
                            userUsername = profile.username,
                        )
                    }
                    if (profile.username != null) loadPublicProfileUrl()
                },
                onFailure = { error ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    val defaultUser = text(R.string.settings_default_user)
                    _uiState.update {
                        it.copy(
                            userName = it.userName.ifBlank { defaultUser },
                            userId = session.ownerUserId,
                            editName = it.editName.ifBlank { defaultUser },
                            errorMessage = error.message ?: text(R.string.settings_user_info_failed),
                        )
                    }
                },
            )
        }
    }


    fun onEditNameChange(name: String) { _uiState.update { it.copy(editName = name.take(30)) } }
    fun startEditing() { _uiState.update { it.copy(isEditing = true, editName = it.userName) } }
    fun cancelEditing() { _uiState.update { it.copy(isEditing = false) } }

    fun openStatusEditor() {
        _uiState.update { it.copy(showStatusDialog = true, editStatus = it.userStatus, errorMessage = null) }
    }

    fun closeStatusEditor() {
        _uiState.update { it.copy(showStatusDialog = false, editStatus = it.userStatus) }
    }

    fun onEditStatusChange(status: String) {
        _uiState.update {
            it.copy(editStatus = status.take(com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH))
        }
    }

    fun applyStatusPreset(preset: String) {
        onEditStatusChange(preset)
    }

    // G358：个人资料一族（状态/昵称/头像）抽到 SettingsProfileController（纯搬移不改判断）。
    private val profileController by lazy {
        SettingsProfileController(
            scope = viewModelScope,
            application = getApplication(),
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
        )
    }

    fun saveStatus() = profileController.saveStatus()

    fun saveProfile() = profileController.saveProfile()

    fun uploadAvatar(uri: Uri) = profileController.uploadAvatar(uri)

    fun removeAvatar() = profileController.removeAvatar()



    fun openBlockedUsers() {
        _uiState.update { it.copy(showBlockedUsersDialog = true) }
        loadBlockedUsers()
    }

    fun closeBlockedUsers() { _uiState.update { it.copy(showBlockedUsersDialog = false) } }

    // G357：「黑名单」管理抽到 SettingsBlockedUsersController（纯搬移不改判断）。
    private val blockedUsersController by lazy {
        SettingsBlockedUsersController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
        )
    }

    fun loadBlockedUsers() = blockedUsersController.loadBlockedUsers()

    fun unblockUser(userId: String) = blockedUsersController.unblockUser(userId)




    // G356：「我的设备」管理抽到 SettingsDeviceController（纯搬移不改判断）。
    private val deviceController by lazy {
        SettingsDeviceController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
        )
    }

    fun loadMyDevices() = deviceController.loadMyDevices()

    fun removeMyDevice(deviceId: Int) = deviceController.removeMyDevice(deviceId)

    fun renameMyDevice(deviceId: Int, name: String) = deviceController.renameMyDevice(deviceId, name)

    fun confirmMyDevice(deviceId: Int) = deviceController.confirmMyDevice(deviceId)



    fun clearSuccessMessage() { _uiState.update { it.copy(successMessage = null) } }
    fun clearErrorMessage() { _uiState.update { it.copy(errorMessage = null) } }

    /** Push non-secret security UX prefs to multi-device blob. */
    fun pushSecurityClientPrefs(
        appLockTimeoutMinutes: Long? = null,
        screenSecureEnabled: Boolean? = null,
        sensitiveGateEnabled: Boolean? = null
    ) {
        if (appLockTimeoutMinutes == null && screenSecureEnabled == null && sensitiveGateEnabled == null) return
        viewModelScope.launch {
            val session = securityCoordinator.currentSession() ?: return@launch
            clientPrefsPushMutex.withLock {
                securityCoordinator.push(
                    session,
                    SecurityPreferencesPatch(
                        appLockTimeoutMinutes = appLockTimeoutMinutes,
                        screenSecureEnabled = screenSecureEnabled,
                        sensitiveGateEnabled = sensitiveGateEnabled,
                    ),
                )
            }
        }
    }

    /** Pull security UX prefs when opening the security center (app-lock enable stays local). */
    fun pullSecurityClientPrefs(
        onApplied: (timeoutMinutes: Long, screenSecure: Boolean, sensitiveGate: Boolean) -> Unit = { _, _, _ -> }
    ) {
        clientPrefsPullJob?.cancel()
        clientPrefsPullJob = viewModelScope.launch {
            val session = securityCoordinator.currentSession() ?: return@launch
            securityCoordinator.pull(session).onSuccess { remote ->
                if (!settingsRepository.isCurrent(session)) return@onSuccess
                val lockTimeout = when (remote.appLockTimeoutMinutes) {
                    1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L -> remote.appLockTimeoutMinutes
                    else -> 5L
                }
                onApplied(lockTimeout, remote.screenSecureEnabled, remote.sensitiveGateEnabled)
            }
        }
    }

    // G359：账号级动作（登出/退出所有设备/注销）抽到 SettingsAccountController（纯搬移不改判断）。
    private val accountController by lazy {
        SettingsAccountController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
        )
    }

    fun logout() = accountController.logout()

    fun logoutAllDevices() = accountController.logoutAllDevices()

    fun deleteAccount(password: String) = accountController.deleteAccount(password)



    /** 加载公开个人主页 URL */
    fun loadPublicProfileUrl() {
        viewModelScope.launch {
            if (!com.maodouchat.session.CurrentSession.hasSession()) return@launch
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!isCurrentOwner(ownerUserId)) return@launch
            accountApi.currentUserPublic().onSuccess { resp ->
                if (!isCurrentOwner(ownerUserId)) return@onSuccess
                _uiState.update {
                    it.copy(
                        publicProfileUrl = resp.publicProfileUrl,
                        userUsername = resp.user?.username
                    )
                }
            }
        }
    }

    fun openUsernameEditor() {
        _uiState.update {
            it.copy(
                showUsernameDialog = true,
                editUsername = it.userUsername ?: ""
            )
        }
    }

    fun closeUsernameEditor() {
        _uiState.update { it.copy(showUsernameDialog = false) }
    }

    fun onEditUsernameChange(value: String) {
        // 只允许字母、数字、下划线、连字符，小写
        val filtered = value.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
            .take(50).lowercase().removePrefix("@")
        _uiState.update { it.copy(editUsername = filtered) }
    }

    fun saveUsername() {
        val username = _uiState.value.editUsername.trim()
        if (username.length < 3) {
            _uiState.update { it.copy(errorMessage = text(R.string.settings_username_too_short)) }
            return
        }
        if (!username.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            _uiState.update { it.copy(errorMessage = text(R.string.settings_username_invalid)) }
            return
        }
        viewModelScope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                // 8.37 修复：此前两分支的 Result 被当表达式语句丢弃、无条件 success——
                // 用户名重复/非法/网络失败被吞掉还显示「已更新」。改为真实返回。
                val result = if (username.isBlank()) {
                    accountApi.clearUsername().map { username }
                } else {
                    accountApi.setUsername(username = username).map { it.username ?: username }
                }
                result.onSuccess {
                    if (!isCurrentOwner(ownerUserId)) return@onSuccess
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showUsernameDialog = false,
                            userUsername = username,
                            successMessage = text(R.string.settings_username_updated)
                        )
                    }
                    loadPublicProfileUrl()
                }
                result.onFailure { error ->
                    if (!isCurrentOwner(ownerUserId)) return@onFailure
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: text(R.string.settings_username_update_failed)
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = e.message ?: text(R.string.settings_username_update_failed)) }
                }
            }
        }
    }

    /** B5 悬浮球开关：未授权时 setEnabled 内部会引导到系统悬浮窗授权页。 */
    fun toggleFloatingBall() {
        val context = getApplication<Application>()
        setFloatingBallEnabled(!com.maodouchat.floating.FloatingBallController.isEnabled(context))
    }

    fun setFloatingBallEnabled(enabled: Boolean) {
        com.maodouchat.floating.FloatingBallController.setEnabled(getApplication(), enabled)
    }
}
