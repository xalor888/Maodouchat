package com.maodouchat.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.maodouchat.explore.policy.ExploreDraftPolicy
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.AndroidSettingsRepository
import com.maodouchat.settings.model.LoadedPrivacy
import com.maodouchat.settings.model.PrivacyField
import com.maodouchat.settings.model.SettingsUiState

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val settingsRepository: SettingsRepository = AndroidSettingsRepository(application),
    private val securityCoordinator: SecurityCoordinator = SecurityCoordinator(settingsRepository),
) : AndroidViewModel(application) {
    /** G328c：账号/设备/拉黑这类**命令式**端点走 data 层仓库（ui 不再直接调 ApiService）。 */
    private val accountApi get() = com.maodouchat.data.repository.AccountSecurityNetworkRepository()

    private var privacySaveJob: Job? = null
    private var loadedPrivacy: LoadedPrivacy? = null
    private val dirtyPrivacyFields = mutableSetOf<PrivacyField>()
    private var accountMutationJob: Job? = null
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

    init {
        loadUserInfo()
        loadPrivacy()
    }

    // G179：回落 PUBLIC（ExploreDraftPolicy 回落 PRIVATE，分歧待决策，详见 SettingsVisibilityPolicy.kt）
    private fun normalizeVisibility(value: String): String = com.maodouchat.settings.normalizeVisibility(value, "PUBLIC")

    /**
     * 隐私开关公共骨架：保存中直接忽略；与已加载值比对决定脏位增减；
     * 最后应用 UI 更新。调用方只给字段、是否干净与更新 lambda。
     */
    private inline fun trackPrivacyField(
        vararg fields: PrivacyField,
        isClean: () -> Boolean,
        update: () -> Unit,
    ) {
        if (_uiState.value.isSavingPrivacy) return
        if (isClean()) {
            dirtyPrivacyFields -= fields.toSet()
        } else {
            dirtyPrivacyFields += fields.toSet()
        }
        update()
    }

    private fun currentLoadedPrivacy() = loadedPrivacy?.takeIf { it.ownerUserId == com.maodouchat.session.CurrentSession.snapshot().userId }

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )

    private fun loadUserInfo() = profileController.loadUserInfo()
    private fun loadPrivacy() {
        viewModelScope.launch {
            val session = settingsRepository.currentSession()
            if (session == null) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            if (loadedPrivacy?.ownerUserId != session.ownerUserId) {
                loadedPrivacy = null
                dirtyPrivacyFields.clear()
            }
            settingsRepository.loadPrivacy(session).fold(
                onSuccess = { privacy ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    val loaded = LoadedPrivacy(
                        ownerUserId = session.ownerUserId,
                        showOnline = privacy.showOnline,
                        showStatus = privacy.showStatus,
                        searchable = privacy.searchable,
                        defaultPostVisibility = normalizeVisibility(privacy.defaultPostVisibility),
                        onlineVisibility = privacy.onlineVisibility.ifBlank {
                            if (privacy.showOnline) "everyone" else "nobody"
                        },
                    )
                    loadedPrivacy = loaded
                    _uiState.update { current ->
                        current.copy(
                            showOnline = if (PrivacyField.SHOW_ONLINE in dirtyPrivacyFields) current.showOnline else loaded.showOnline,
                            onlineVisibility = if (PrivacyField.ONLINE_VISIBILITY in dirtyPrivacyFields) current.onlineVisibility else loaded.onlineVisibility,
                            showStatus = if (PrivacyField.SHOW_STATUS in dirtyPrivacyFields) current.showStatus else loaded.showStatus,
                            searchable = if (PrivacyField.SEARCHABLE in dirtyPrivacyFields) current.searchable else loaded.searchable,
                            defaultPostVisibility = if (PrivacyField.DEFAULT_POST_VISIBILITY in dirtyPrivacyFields) {
                                current.defaultPostVisibility
                            } else {
                                loaded.defaultPostVisibility
                            },
                        )
                    }
                },
                onFailure = { error ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: text(R.string.settings_privacy_load_failed))
                    }
                },
            )
        }
    }

    fun onEditNameChange(name: String) = profileController.onEditNameChange(name)
    fun startEditing() = profileController.startEditing()
    fun cancelEditing() = profileController.cancelEditing()

    fun openStatusEditor() = profileController.openStatusEditor()

    fun closeStatusEditor() = profileController.closeStatusEditor()

    fun onEditStatusChange(status: String) = profileController.onEditStatusChange(status)

    fun applyStatusPreset(preset: String) = profileController.applyStatusPreset(preset)
    fun saveStatus() = profileController.saveStatus()
    fun saveProfile() = profileController.saveProfile()
    fun uploadAvatar(uri: Uri) = profileController.uploadAvatar(uri)
    fun openPrivacy() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (loadedPrivacy?.ownerUserId != ownerUserId) loadPrivacy()
        _uiState.update { it.copy(showPrivacyDialog = true) }
    }

    fun closePrivacy() {
        if (_uiState.value.isSavingPrivacy) return
        val baseline = loadedPrivacy?.takeIf { it.ownerUserId == com.maodouchat.session.CurrentSession.snapshot().userId }
        dirtyPrivacyFields.clear()
        _uiState.update {
            it.copy(
                showPrivacyDialog = false,
                showOnline = baseline?.showOnline ?: it.showOnline,
                onlineVisibility = baseline?.onlineVisibility ?: it.onlineVisibility,
                showStatus = baseline?.showStatus ?: it.showStatus,
                searchable = baseline?.searchable ?: it.searchable,
                defaultPostVisibility = baseline?.defaultPostVisibility ?: it.defaultPostVisibility
            )
        }
    }

    fun onShowOnlineChange(v: Boolean) {
        if (_uiState.value.isSavingPrivacy) return
        val vis = if (v) {
            _uiState.value.onlineVisibility.takeUnless { it == "nobody" } ?: "everyone"
        } else {
            "nobody"
        }
        onOnlineVisibilityChange(vis)
    }

    fun onOnlineVisibilityChange(v: String) {
        val normalized = when (v) {
            "contacts", "nobody" -> v
            else -> "everyone"
        }
        trackPrivacyField(
            PrivacyField.ONLINE_VISIBILITY, PrivacyField.SHOW_ONLINE,
            isClean = { currentLoadedPrivacy()?.onlineVisibility == normalized },
        ) {
            _uiState.update { it.copy(onlineVisibility = normalized, showOnline = normalized != "nobody") }
        }
    }

    fun onShowStatusChange(v: Boolean) {
        trackPrivacyField(
            PrivacyField.SHOW_STATUS,
            isClean = { currentLoadedPrivacy()?.showStatus == v },
        ) {
            _uiState.update { it.copy(showStatus = v) }
        }
    }

    fun onSearchableChange(v: Boolean) {
        trackPrivacyField(
            PrivacyField.SEARCHABLE,
            isClean = { currentLoadedPrivacy()?.searchable == v },
        ) {
            _uiState.update { it.copy(searchable = v) }
        }
    }

    fun onDefaultVisibilityChange(v: String) {
        val normalized = normalizeVisibility(v)
        trackPrivacyField(
            PrivacyField.DEFAULT_POST_VISIBILITY,
            isClean = { currentLoadedPrivacy()?.defaultPostVisibility == normalized },
        ) {
            _uiState.update { it.copy(defaultPostVisibility = normalized) }
        }
    }

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


    // G358：「个人资料/头像」管理抽到 SettingsProfileController（纯搬移不改判断）。
    private val profileController by lazy {
        SettingsProfileController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            isCurrentOwner = { owner -> isCurrentOwner(owner) },
            appContext = getApplication(),
            settingsRepository = settingsRepository,
            reloadPublicProfileUrl = { loadPublicProfileUrl() },
        )
    }


    fun removeAvatar() = profileController.removeAvatar()

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


    fun savePrivacy() {
        if (privacySaveJob?.isActive == true) return
        privacySaveJob = viewModelScope.launch {
            val session = settingsRepository.currentSession()
            val privacyOwnerUserId = session?.ownerUserId.orEmpty()
            if (session == null) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isSavingPrivacy = true, errorMessage = null) }
            // 在 isSavingPrivacy=true 之后才快照，避免并发 toggle 导致保存旧状态
            val snapshot = _uiState.value
            val changedFields = dirtyPrivacyFields.toSet()
            if (changedFields.isEmpty()) {
                _uiState.update { it.copy(isSavingPrivacy = false, showPrivacyDialog = false) }
                return@launch
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = privacyOwnerUserId,
                )
                ) {
                    return@launch
                }
                settingsRepository.savePrivacy(
                    session,
                    SettingsPrivacyPatch(
                        showOnline = snapshot.showOnline.takeIf { PrivacyField.SHOW_ONLINE in changedFields },
                        showStatus = snapshot.showStatus.takeIf { PrivacyField.SHOW_STATUS in changedFields },
                        searchable = snapshot.searchable.takeIf { PrivacyField.SEARCHABLE in changedFields },
                        defaultPostVisibility = snapshot.defaultPostVisibility.takeIf {
                            PrivacyField.DEFAULT_POST_VISIBILITY in changedFields
                        },
                        onlineVisibility = snapshot.onlineVisibility.takeIf {
                            PrivacyField.ONLINE_VISIBILITY in changedFields
                        },
                    ),
                ).fold(
                    onSuccess = { privacy ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = privacyOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        val normalizedVisibility = normalizeVisibility(privacy.defaultPostVisibility)
                        loadedPrivacy = LoadedPrivacy(
                            ownerUserId = privacyOwnerUserId,
                            showOnline = privacy.showOnline,
                            showStatus = privacy.showStatus,
                            searchable = privacy.searchable,
                            defaultPostVisibility = normalizedVisibility,
                            onlineVisibility = privacy.onlineVisibility.ifBlank { if (privacy.showOnline) "everyone" else "nobody" }
                        )
                        dirtyPrivacyFields.clear()
                        _uiState.update {
                            it.copy(
                                showOnline = privacy.showOnline,
                                onlineVisibility = privacy.onlineVisibility.ifBlank { if (privacy.showOnline) "everyone" else "nobody" },
                                showStatus = privacy.showStatus,
                                searchable = privacy.searchable,
                                defaultPostVisibility = normalizedVisibility,
                                showPrivacyDialog = false,
                                isSavingPrivacy = false,
                                successMessage = text(R.string.settings_privacy_saved)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(privacyOwnerUserId)) return@fold
                        _uiState.update { it.copy(isSavingPrivacy = false, errorMessage = error.message ?: text(R.string.settings_privacy_save_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(privacyOwnerUserId)) {
                    _uiState.update { it.copy(isSavingPrivacy = false) }
                }
                throw error
            }
        }
    }

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

    fun logout() {
        if (accountMutationJob?.isActive == true) return
        // 9.140：快照当前账号并带归属校验 purge——此前无 expectedOwnerUserId，
        // 按钮点击到协程体执行之间换号会把新账号会话一并清掉
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        accountMutationJob = viewModelScope.launch {
            withContext(NonCancellable) {
                com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                    destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                        com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                    ),
                    expectedOwnerUserId = ownerUserId.takeIf { it.isNotBlank() }
                )
                // 1.103：登出清空「正在输入」presence，避免残留对端状态。
                // 放在 NonCancellable 内，避免 purge 后协程取消导致清理被跳过。
                com.maodouchat.util.TypingPresenceStore.clear()
            }
            _uiState.update { it.copy(isLoggedOut = true) }
        }
    }

    /**
     * 8.62：退出所有设备（设备丢失/被盗时远程撤销全部会话，含当前设备）。
     * 服务端已吊销当前会话——成功后本地必须 purge，否则残留假登录态。
     */
    fun logoutAllDevices() {
        if (accountMutationJob?.isActive == true) return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        accountMutationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOutAll = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update { it.copy(isLoggingOutAll = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.logoutAll().fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        withContext(NonCancellable) {
                            com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                                ),
                                expectedOwnerUserId = ownerUserId
                            )
                            com.maodouchat.util.TypingPresenceStore.clear()
                        }
                        _uiState.update { it.copy(isLoggingOutAll = false, isLoggedOut = true) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(isLoggingOutAll = false, errorMessage = error.message ?: text(R.string.settings_logout_all_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isLoggingOutAll = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isLoggingOutAll = false, errorMessage = error.message ?: text(R.string.settings_logout_all_failed)) }
                }
            }
        }
    }

    fun deleteAccount(password: String) {
        if (accountMutationJob?.isActive == true) return
        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.settings_enter_current_password)) }
            return
        }
        accountMutationJob = viewModelScope.launch {
            val deleteOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || deleteOwnerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isDeletingAccount = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = deleteOwnerUserId,
                )
                ) {
                    // 8.38：门禁失败复位 isDeletingAccount，否则删号弹窗永久转圈
                    _uiState.update { it.copy(isDeletingAccount = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.deleteAccount(password = password).fold(
                    onSuccess = {
                        if (!isCurrentOwner(deleteOwnerUserId)) return@fold
                        val purged = withContext(kotlinx.coroutines.NonCancellable) {
                            val result = com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.DELETE_ACCOUNT
                                ),
                                expectedOwnerUserId = deleteOwnerUserId
                            )
                            com.maodouchat.util.TypingPresenceStore.clear()
                            result
                        }
                        // 8.33 修复：服务端账号已删除，即使本地 purge 失败也不能卡死在 loading——
                        // 提示用户手动登出（重试会因服务端已删而报错）。
                        _uiState.update {
                            it.copy(
                                isDeletingAccount = false,
                                isLoggedOut = true,
                                successMessage = if (purged) {
                                    text(R.string.settings_account_deleted)
                                } else {
                                    text(R.string.settings_account_deleted_local_purge_failed)
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(deleteOwnerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isDeletingAccount = false,
                                errorMessage = error.message ?: text(R.string.settings_account_delete_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(deleteOwnerUserId)) {
                    _uiState.update { it.copy(isDeletingAccount = false) }
                }
                throw error
            }
        }
    }


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
