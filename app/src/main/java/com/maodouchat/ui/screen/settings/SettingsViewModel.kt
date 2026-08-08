package com.maodouchat.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.UserDto
import com.maodouchat.network.DeviceInfoDto
import com.maodouchat.network.TokenManager
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.Dispatchers
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

data class SettingsUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val userStatus: String = "",
    val userUsername: String? = null,
    val publicProfileUrl: String? = null,
    val isModerator: Boolean = false,
    val isLoggedOut: Boolean = false,
    val isEditing: Boolean = false,
    val editName: String = "",
    val showUsernameDialog: Boolean = false,
    val editUsername: String = "",
    val showPrivacyDialog: Boolean = false,
    val showStatusDialog: Boolean = false,
    val showBlockedUsersDialog: Boolean = false,
    val showOnline: Boolean = true,
    val showStatus: Boolean = true,
    val editStatus: String = "",
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC",
    val blockedUsers: List<UserDto> = emptyList(),
    val devices: List<DeviceInfoDto> = emptyList(),
    val currentDeviceId: Int = 1,
    val isUploading: Boolean = false,
    val isSaving: Boolean = false,
    val isSavingPrivacy: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val isLoggingOutAll: Boolean = false,
    val isLoadingBlockedUsers: Boolean = false,
    val isUpdatingBlockedUsers: Boolean = false,
    val isLoadingDevices: Boolean = false,
    val removingDeviceId: Int? = null,
    val renamingDeviceId: Int? = null,
    val confirmingDeviceId: Int? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

private enum class PrivacyField { SHOW_ONLINE, SHOW_STATUS, SEARCHABLE, DEFAULT_POST_VISIBILITY }

private data class LoadedPrivacy(
    val ownerUserId: String,
    val showOnline: Boolean,
    val showStatus: Boolean,
    val searchable: Boolean,
    val defaultPostVisibility: String
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    internal val tokenManager = TokenManager.getInstance(application)
    private val app = application as MaodouchatApp
    private var profileSaveJob: Job? = null
    private var avatarUploadJob: Job? = null
    private var blockedUsersLoadJob: Job? = null
    private var blockedUsersMutationJob: Job? = null
    private var devicesLoadJob: Job? = null
    private var deviceMutationJob: Job? = null
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

    private fun normalizeVisibility(value: String): String {
        return if (value in visibilityOptions.map { it.first }) value else "PUBLIC"
    }

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    private fun loadUserInfo() {
        viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                val defaultUser = text(R.string.settings_default_user)
                _uiState.update {
                    it.copy(
                        userName = defaultUser,
                        userId = ownerUserId,
                        editName = defaultUser,
                        errorMessage = text(R.string.error_session_expired)
                    )
                }
                return@launch
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken() ?: token
            val result = ApiService.getCurrentUser(liveToken)
            result.onSuccess { me ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        userName = me.name,
                        userId = me.id,
                        userAvatar = me.avatar,
                        userStatus = me.status,
                        editStatus = me.status,
                        isModerator = me.isModerator,
                        editName = me.name,
                        userUsername = me.username
                    )
                }
                // 加载用户名和公开主页 URL
                if (me.username != null) {
                    loadPublicProfileUrl()
                }
            }
            result.onFailure { error ->
                if (!isCurrentOwner(ownerUserId)) return@onFailure
                _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.settings_user_info_failed)) }
            }
            if (isCurrentOwner(ownerUserId) && _uiState.value.userName.isBlank()) {
                val defaultUser = text(R.string.settings_default_user)
                _uiState.update { it.copy(userName = defaultUser, userId = ownerUserId, editName = defaultUser) }
            }
        }
    }

    private fun loadPrivacy() {
        viewModelScope.launch {
            val token = tokenManager.getToken()
            val privacyOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || privacyOwnerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            if (loadedPrivacy?.ownerUserId != privacyOwnerUserId) {
                loadedPrivacy = null
                dirtyPrivacyFields.clear()
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = privacyOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken() ?: token
            ApiService.getPrivacy(liveToken).fold(
                onSuccess = { privacy ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = privacyOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    val loaded = LoadedPrivacy(
                        ownerUserId = privacyOwnerUserId,
                        showOnline = privacy.showOnline,
                        showStatus = privacy.showStatus,
                        searchable = privacy.searchable,
                        defaultPostVisibility = normalizeVisibility(privacy.defaultPostVisibility)
                    )
                    loadedPrivacy = loaded
                    _uiState.update { current ->
                        current.copy(
                            showOnline = if (PrivacyField.SHOW_ONLINE in dirtyPrivacyFields) current.showOnline else loaded.showOnline,
                            showStatus = if (PrivacyField.SHOW_STATUS in dirtyPrivacyFields) current.showStatus else loaded.showStatus,
                            searchable = if (PrivacyField.SEARCHABLE in dirtyPrivacyFields) current.searchable else loaded.searchable,
                            defaultPostVisibility = if (PrivacyField.DEFAULT_POST_VISIBILITY in dirtyPrivacyFields) {
                                current.defaultPostVisibility
                            } else {
                                loaded.defaultPostVisibility
                            }
                        )
                    }
                },
                onFailure = { error ->
                    if (!isCurrentOwner(privacyOwnerUserId)) return@fold
                    // Keep local defaults; surface soft error so user can retry save/reload path.
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: text(R.string.settings_privacy_load_failed))
                    }
                }
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

    fun saveStatus() {
        if (profileSaveJob?.isActive == true) return
        val status = com.maodouchat.util.CustomStatusPolicy.normalize(_uiState.value.editStatus)
        if (!com.maodouchat.util.CustomStatusPolicy.isValid(_uiState.value.editStatus)) {
            _uiState.update { it.copy(errorMessage = text(R.string.status_too_long)) }
            return
        }
        profileSaveJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val profileOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || profileOwnerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = profileOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updateProfile(liveToken, status = status).fold(
                    onSuccess = { user ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = profileOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                userStatus = user.status,
                                editStatus = user.status,
                                showStatusDialog = false,
                                isSaving = false,
                                successMessage = text(R.string.status_saved)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(profileOwnerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = text(
                                    R.string.status_save_failed,
                                    error.message ?: text(R.string.call_unknown_error)
                                )
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(profileOwnerUserId)) {
                    _uiState.update { it.copy(isSaving = false) }
                }
                throw error
            }
        }
    }

    fun saveProfile() {
        if (profileSaveJob?.isActive == true) return
        val name = _uiState.value.editName.trim()
        if (name.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.settings_nickname_empty)) }; return }
        profileSaveJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val profileOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || profileOwnerUserId.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }; return@launch }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = profileOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updateProfile(liveToken, name = name).fold(
                    onSuccess = { user ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = profileOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(userName = user.name, userAvatar = user.avatar, userStatus = user.status, editName = user.name, isEditing = false, isSaving = false, successMessage = text(R.string.settings_profile_saved)) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(profileOwnerUserId)) return@fold
                        _uiState.update { it.copy(isSaving = false, errorMessage = text(R.string.settings_profile_save_failed, error.message ?: text(R.string.call_unknown_error))) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(profileOwnerUserId)) {
                    _uiState.update { it.copy(isSaving = false) }
                }
                throw error
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        if (avatarUploadJob?.isActive == true) return
        val uploadOwnerUserId = tokenManager.getUserId().orEmpty()
        if (uploadOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        avatarUploadJob = viewModelScope.launch {
            if (!isCurrentOwner(uploadOwnerUserId)) return@launch
            _uiState.update { it.copy(isUploading = true) }
            try {
                val base64 = withContext(Dispatchers.IO) {
                    ImagePicker.uriToBase64(getApplication(), uri, maxWidth = 400, quality = 80)
                }
                if (base64 != null) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = uploadOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        if (tokenManager.getUserId() == uploadOwnerUserId) {
                            _uiState.update {
                                it.copy(isUploading = false, errorMessage = text(R.string.error_session_expired))
                            }
                        }
                        return@launch
                    }
                    val token = tokenManager.getToken()
                    if (token.isNullOrBlank()) { _uiState.update { it.copy(isUploading = false, errorMessage = text(R.string.error_session_expired)) }; return@launch }
                    ApiService.uploadAvatar(token, base64).fold(
                        onSuccess = { url ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = uploadOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@fold
                            }
                            _uiState.update { it.copy(userAvatar = url, isUploading = false, successMessage = text(R.string.settings_avatar_updated)) }
                        },
                        onFailure = { error ->
                            if (!isCurrentOwner(uploadOwnerUserId)) return@fold
                            _uiState.update { it.copy(isUploading = false, errorMessage = text(R.string.settings_upload_failed, error.message ?: text(R.string.call_unknown_error))) }
                        }
                    )
                } else if (isCurrentOwner(uploadOwnerUserId)) {
                    _uiState.update { it.copy(isUploading = false, errorMessage = text(R.string.settings_image_process_failed)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(uploadOwnerUserId)) {
                    _uiState.update { it.copy(isUploading = false) }
                }
                throw error
            } catch (_: Exception) {
                if (tokenManager.getUserId() == uploadOwnerUserId) {
                    _uiState.update {
                        it.copy(isUploading = false, errorMessage = text(R.string.settings_image_process_failed))
                    }
                }
            }
        }
    }

    fun openPrivacy() {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (loadedPrivacy?.ownerUserId != ownerUserId) loadPrivacy()
        _uiState.update { it.copy(showPrivacyDialog = true) }
    }

    fun closePrivacy() {
        if (_uiState.value.isSavingPrivacy) return
        val baseline = loadedPrivacy?.takeIf { it.ownerUserId == tokenManager.getUserId() }
        dirtyPrivacyFields.clear()
        _uiState.update {
            it.copy(
                showPrivacyDialog = false,
                showOnline = baseline?.showOnline ?: it.showOnline,
                showStatus = baseline?.showStatus ?: it.showStatus,
                searchable = baseline?.searchable ?: it.searchable,
                defaultPostVisibility = baseline?.defaultPostVisibility ?: it.defaultPostVisibility
            )
        }
    }

    fun onShowOnlineChange(v: Boolean) {
        if (_uiState.value.isSavingPrivacy) return
        if (loadedPrivacy?.takeIf { it.ownerUserId == tokenManager.getUserId() }?.showOnline == v) {
            dirtyPrivacyFields -= PrivacyField.SHOW_ONLINE
        } else {
            dirtyPrivacyFields += PrivacyField.SHOW_ONLINE
        }
        _uiState.update { it.copy(showOnline = v) }
    }

    fun onShowStatusChange(v: Boolean) {
        if (_uiState.value.isSavingPrivacy) return
        if (loadedPrivacy?.takeIf { it.ownerUserId == tokenManager.getUserId() }?.showStatus == v) {
            dirtyPrivacyFields -= PrivacyField.SHOW_STATUS
        } else {
            dirtyPrivacyFields += PrivacyField.SHOW_STATUS
        }
        _uiState.update { it.copy(showStatus = v) }
    }

    fun onSearchableChange(v: Boolean) {
        if (_uiState.value.isSavingPrivacy) return
        if (loadedPrivacy?.takeIf { it.ownerUserId == tokenManager.getUserId() }?.searchable == v) {
            dirtyPrivacyFields -= PrivacyField.SEARCHABLE
        } else {
            dirtyPrivacyFields += PrivacyField.SEARCHABLE
        }
        _uiState.update { it.copy(searchable = v) }
    }

    fun onDefaultVisibilityChange(v: String) {
        if (_uiState.value.isSavingPrivacy) return
        val normalized = normalizeVisibility(v)
        if (loadedPrivacy?.takeIf { it.ownerUserId == tokenManager.getUserId() }?.defaultPostVisibility == normalized) {
            dirtyPrivacyFields -= PrivacyField.DEFAULT_POST_VISIBILITY
        } else {
            dirtyPrivacyFields += PrivacyField.DEFAULT_POST_VISIBILITY
        }
        _uiState.update { it.copy(defaultPostVisibility = normalized) }
    }

    fun openBlockedUsers() {
        _uiState.update { it.copy(showBlockedUsersDialog = true) }
        loadBlockedUsers()
    }

    fun closeBlockedUsers() { _uiState.update { it.copy(showBlockedUsersDialog = false) } }

    fun loadBlockedUsers() {
        blockedUsersLoadJob?.cancel()
        blockedUsersLoadJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                _uiState.update {
                    it.copy(isLoadingBlockedUsers = false, errorMessage = text(R.string.error_session_expired))
                }
                return@launch
            }
            _uiState.update { it.copy(isLoadingBlockedUsers = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (tokenManager.getUserId() == ownerUserId) {
                        _uiState.update {
                            it.copy(isLoadingBlockedUsers = false, errorMessage = text(R.string.error_session_expired))
                        }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getBlockedUserDetails(liveToken).fold(
                    onSuccess = { users ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(blockedUsers = users, isLoadingBlockedUsers = false) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(isLoadingBlockedUsers = false, errorMessage = error.message ?: text(R.string.settings_blocked_load_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isLoadingBlockedUsers = false) }
                }
                throw error
            }
        }
    }

    fun removeAvatar() {
        if (avatarUploadJob?.isActive == true || _uiState.value.userAvatar.isNullOrBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken()
        if (ownerUserId.isBlank() || token.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        avatarUploadJob = viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isUploading = true) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.removeAvatar(liveToken).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                userAvatar = null,
                                isUploading = false,
                                successMessage = text(R.string.settings_avatar_removed)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                errorMessage = text(
                                    R.string.settings_avatar_remove_failed,
                                    error.message ?: text(R.string.call_unknown_error)
                                )
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isUploading = false) }
                throw error
            }
        }
    }

    fun unblockUser(userId: String) {
        if (blockedUsersMutationJob?.isActive == true) return
        blockedUsersMutationJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }; return@launch }
            _uiState.update { it.copy(isUpdatingBlockedUsers = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.unblockUser(liveToken, userId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                blockedUsers = it.blockedUsers.filterNot { user -> user.id == userId },
                                isUpdatingBlockedUsers = false,
                                successMessage = text(R.string.settings_unblocked)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(isUpdatingBlockedUsers = false, errorMessage = error.message ?: text(R.string.chat_unblock_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isUpdatingBlockedUsers = false) }
                }
                throw error
            }
        }
    }

    fun loadMyDevices() {
        devicesLoadJob?.cancel()
        devicesLoadJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val userId = tokenManager.getUserId()
            if (token.isNullOrBlank() || userId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(isLoadingDevices = false, errorMessage = text(R.string.error_session_expired))
                }
                return@launch
            }
            val currentDeviceId = app.signalProtocol.getDeviceId()
            _uiState.update { it.copy(isLoadingDevices = true, currentDeviceId = currentDeviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = userId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (tokenManager.getUserId() == userId) {
                        _uiState.update {
                            it.copy(isLoadingDevices = false, errorMessage = text(R.string.error_session_expired))
                        }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getDevices(liveToken, userId, currentDeviceId).fold(
                    onSuccess = { devices ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = userId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                devices = devices.sortedWith(compareByDescending<DeviceInfoDto> { d -> d.isCurrent }.thenBy { d -> d.deviceId }),
                                isLoadingDevices = false
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(userId)) return@fold
                        _uiState.update { it.copy(isLoadingDevices = false, errorMessage = error.message ?: text(R.string.settings_device_list_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(userId)) {
                    _uiState.update { it.copy(isLoadingDevices = false) }
                }
                throw error
            }
        }
    }

    fun removeMyDevice(deviceId: Int) {
        if (deviceMutationJob?.isActive == true) return
        deviceMutationJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            if (deviceId == _uiState.value.currentDeviceId) {
                _uiState.update { it.copy(errorMessage = text(R.string.settings_cannot_remove_current_device)) }
                return@launch
            }
            _uiState.update { it.copy(removingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    // 8.38：门禁失败复位 removingDeviceId
                    _uiState.update { it.copy(removingDeviceId = null, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.removeMyDevice(liveToken, deviceId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                devices = it.devices.filterNot { device -> device.deviceId == deviceId },
                                removingDeviceId = null,
                                successMessage = text(R.string.settings_device_removed)
                            )
                        }
                        loadMyDevices()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(removingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_remove_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(removingDeviceId = null) }
                }
                throw error
            }
        }
    }

    fun renameMyDevice(deviceId: Int, name: String) {
        if (deviceMutationJob?.isActive == true) return
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 50) {
            _uiState.update { it.copy(errorMessage = text(R.string.settings_device_name_length)) }
            return
        }
        deviceMutationJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(renamingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    // 8.38：门禁失败复位 renamingDeviceId
                    _uiState.update { it.copy(renamingDeviceId = null, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.renameMyDevice(liveToken, deviceId, trimmed).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                devices = it.devices.map { device -> if (device.deviceId == deviceId) device.copy(deviceName = trimmed) else device },
                                renamingDeviceId = null,
                                successMessage = text(R.string.settings_device_name_updated)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(renamingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_name_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(renamingDeviceId = null) }
                }
                throw error
            }
        }
    }

    fun confirmMyDevice(deviceId: Int) {
        if (deviceMutationJob?.isActive == true) return
        deviceMutationJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            val approverDeviceId = app.signalProtocol.getDeviceId()
            val currentDevice = _uiState.value.devices.firstOrNull { it.deviceId == approverDeviceId }
            val targetDevice = _uiState.value.devices.firstOrNull { it.deviceId == deviceId }
            if (deviceId == approverDeviceId || currentDevice?.status != "CONFIRMED") {
                _uiState.update { it.copy(errorMessage = text(R.string.settings_approve_from_confirmed_device)) }
                return@launch
            }
            val approvalSignature = targetDevice?.identityKey
                ?.let { app.signalProtocol.signDeviceConfirmation(deviceId, it) }
            if (approvalSignature.isNullOrBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.settings_device_confirm_proof_failed)) }
                return@launch
            }
            _uiState.update { it.copy(confirmingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.confirmMyDevice(liveToken, deviceId, approverDeviceId, approvalSignature).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                devices = it.devices.map { device ->
                                    if (device.deviceId == deviceId) {
                                        device.copy(status = "CONFIRMED", confirmedAt = System.currentTimeMillis(), confirmedByDeviceId = approverDeviceId)
                                    } else {
                                        device
                                    }
                                },
                                confirmingDeviceId = null,
                                successMessage = text(R.string.settings_device_confirmed)
                            )
                        }
                        loadMyDevices()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(confirmingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_confirm_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(confirmingDeviceId = null) }
                }
                throw error
            }
        }
    }

    fun savePrivacy() {
        if (privacySaveJob?.isActive == true) return
        privacySaveJob = viewModelScope.launch {
            val token = tokenManager.getToken()
            val privacyOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || privacyOwnerUserId.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }; return@launch }
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updatePrivacy(
                    liveToken,
                    showOnline = snapshot.showOnline.takeIf { PrivacyField.SHOW_ONLINE in changedFields },
                    showStatus = snapshot.showStatus.takeIf { PrivacyField.SHOW_STATUS in changedFields },
                    searchable = snapshot.searchable.takeIf { PrivacyField.SEARCHABLE in changedFields },
                    defaultPostVisibility = snapshot.defaultPostVisibility.takeIf {
                        PrivacyField.DEFAULT_POST_VISIBILITY in changedFields
                    }
                ).fold(
                    onSuccess = { privacy ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = privacyOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
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
                            defaultPostVisibility = normalizedVisibility
                        )
                        dirtyPrivacyFields.clear()
                        _uiState.update {
                            it.copy(
                                showOnline = privacy.showOnline,
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
            val token = tokenManager.getToken().orEmpty()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isBlank() || ownerUserId.isBlank()) return@launch
            clientPrefsPushMutex.withLock {
                if (!isCurrentOwner(ownerUserId)) return@withLock
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.putClientPrefs(
                    liveToken,
                    com.maodouchat.network.ClientPrefsUpdateRequest(
                        appLockTimeoutMinutes = appLockTimeoutMinutes,
                        screenSecureEnabled = screenSecureEnabled,
                        sensitiveGateEnabled = sensitiveGateEnabled
                    )
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
            val token = tokenManager.getToken().orEmpty()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isBlank() || ownerUserId.isBlank()) return@launch
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val context = getApplication<Application>()
            ApiService.getClientPrefs(liveToken).onSuccess { remote ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) return@onSuccess
                com.maodouchat.util.ClientPrefsSync.apply(context, remote)
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
        accountMutationJob = viewModelScope.launch {
            withContext(NonCancellable) {
                com.maodouchat.network.WebSocketClient.disconnect()
                app.secureSessionManager.purgeLocalSession()
            }
            // 1.103：登出清空「正在输入」presence，避免残留对端状态
            com.maodouchat.util.TypingPresenceStore.clear()
            _uiState.update { it.copy(isLoggedOut = true) }
        }
    }

    /**
     * 8.62：退出所有设备（设备丢失/被盗时远程撤销全部会话，含当前设备）。
     * 服务端已吊销当前会话——成功后本地必须 purge，否则残留假登录态。
     */
    fun logoutAllDevices() {
        if (accountMutationJob?.isActive == true) return
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        accountMutationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOutAll = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isLoggingOutAll = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.logoutAll(liveToken).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        withContext(NonCancellable) {
                            com.maodouchat.network.WebSocketClient.disconnect()
                            app.secureSessionManager.purgeLocalSession(expectedOwnerUserId = ownerUserId)
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
            val token = tokenManager.getToken()
            val deleteOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || deleteOwnerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isDeletingAccount = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    // 8.38：门禁失败复位 isDeletingAccount，否则删号弹窗永久转圈
                    _uiState.update { it.copy(isDeletingAccount = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.deleteAccount(liveToken, password).fold(
                    onSuccess = {
                        if (!isCurrentOwner(deleteOwnerUserId)) return@fold
                        val purged = app.secureSessionManager.purgeLocalSession(
                            expectedOwnerUserId = deleteOwnerUserId
                        )
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

    // ─── 用户名设置 ──────────────────────────

    /** 加载公开个人主页 URL */
    fun loadPublicProfileUrl() {
        viewModelScope.launch {
            val token = tokenManager.getToken()
            if (token.isNullOrBlank()) return@launch
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (!isCurrentOwner(ownerUserId)) return@launch
            val liveToken = tokenManager.getToken() ?: token
            ApiService.getCurrentUserPublic(liveToken).onSuccess { resp ->
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
            val token = tokenManager.getToken()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isNullOrBlank() || ownerUserId.isBlank()) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken() ?: token
                // 8.37 修复：此前两分支的 Result 被当表达式语句丢弃、无条件 success——
                // 用户名重复/非法/网络失败被吞掉还显示「已更新」。改为真实返回。
                val result = if (username.isBlank()) {
                    ApiService.clearUsername(liveToken).map { username }
                } else {
                    ApiService.setUsername(liveToken, username).map { it.username ?: username }
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
        val context = app
        val enabled = com.maodouchat.floating.FloatingBallController.isEnabled(context)
        com.maodouchat.floating.FloatingBallController.setEnabled(context, !enabled)
    }
}
