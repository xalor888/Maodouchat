package com.maodouchat.ui.screen.settings

import android.app.Application
import android.net.Uri
import com.maodouchat.R
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.settings.model.SettingsUiState
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsProfileController(
    private val scope: CoroutineScope,
    private val application: Application,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val accountApi: AccountSecurityNetworkRepository = AccountSecurityNetworkRepository(),
) {
    private var profileSaveJob: Job? = null
    private var avatarUploadJob: Job? = null

    fun saveStatus() {
        if (profileSaveJob?.isActive == true) return
        val status = com.maodouchat.util.CustomStatusPolicy.normalize(currentState().editStatus)
        if (!com.maodouchat.util.CustomStatusPolicy.isValid(currentState().editStatus)) {
            updateState { it.copy(errorMessage = text(R.string.status_too_long)) }
            return
        }
        profileSaveJob = scope.launch {
            val profileOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || profileOwnerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            updateState { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = profileOwnerUserId,
                )
                ) {
                    return@launch
                }
                accountApi.updateProfile(status = status).fold(
                    onSuccess = { user ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = profileOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
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
                        updateState {
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
                    updateState { it.copy(isSaving = false) }
                }
                throw error
            }
        }
    }

    fun saveProfile() {
        if (profileSaveJob?.isActive == true) return
        val name = currentState().editName.trim()
        if (name.isBlank()) { updateState { it.copy(errorMessage = text(R.string.settings_nickname_empty)) }; return }
        profileSaveJob = scope.launch {
            val profileOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || profileOwnerUserId.isBlank()) { updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }; return@launch }
            updateState { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = profileOwnerUserId,
                )
                ) {
                    return@launch
                }
                accountApi.updateProfile(name = name).fold(
                    onSuccess = { user ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = profileOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState { it.copy(userName = user.name, userAvatar = user.avatar, userStatus = user.status, editName = user.name, isEditing = false, isSaving = false, successMessage = text(R.string.settings_profile_saved)) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(profileOwnerUserId)) return@fold
                        updateState { it.copy(isSaving = false, errorMessage = text(R.string.settings_profile_save_failed, error.message ?: text(R.string.call_unknown_error))) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(profileOwnerUserId)) {
                    updateState { it.copy(isSaving = false) }
                }
                throw error
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        if (avatarUploadJob?.isActive == true) return
        val uploadOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (uploadOwnerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        avatarUploadJob = scope.launch {
            if (!isCurrentOwner(uploadOwnerUserId)) return@launch
            updateState { it.copy(isUploading = true) }
            try {
                val base64 = withContext(Dispatchers.IO) {
                    ImagePicker.uriToBase64(application, uri, maxWidth = 400, quality = 80)
                }
                if (base64 != null) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = uploadOwnerUserId,
                    )
                    ) {
                        if (com.maodouchat.session.CurrentSession.snapshot().userId == uploadOwnerUserId) {
                            updateState {
                                it.copy(isUploading = false, errorMessage = text(R.string.error_session_expired))
                            }
                        }
                        return@launch
                    }
                    if (!com.maodouchat.session.CurrentSession.hasSession()) { updateState { it.copy(isUploading = false, errorMessage = text(R.string.error_session_expired)) }; return@launch }
                    accountApi.uploadAvatar(base64Data = base64).fold(
                        onSuccess = { url ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = uploadOwnerUserId,
                            )
                            ) {
                                return@fold
                            }
                            updateState { it.copy(userAvatar = url, isUploading = false, successMessage = text(R.string.settings_avatar_updated)) }
                        },
                        onFailure = { error ->
                            if (!isCurrentOwner(uploadOwnerUserId)) return@fold
                            updateState { it.copy(isUploading = false, errorMessage = text(R.string.settings_upload_failed, error.message ?: text(R.string.call_unknown_error))) }
                        }
                    )
                } else if (isCurrentOwner(uploadOwnerUserId)) {
                    updateState { it.copy(isUploading = false, errorMessage = text(R.string.settings_image_process_failed)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(uploadOwnerUserId)) {
                    updateState { it.copy(isUploading = false) }
                }
                throw error
            } catch (_: Exception) {
                if (com.maodouchat.session.CurrentSession.snapshot().userId == uploadOwnerUserId) {
                    updateState {
                        it.copy(isUploading = false, errorMessage = text(R.string.settings_image_process_failed))
                    }
                }
            }
        }
    }

    fun removeAvatar() {
        if (avatarUploadJob?.isActive == true || currentState().userAvatar.isNullOrBlank()) return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (ownerUserId.isBlank() || !com.maodouchat.session.CurrentSession.hasSession()) {
            updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        avatarUploadJob = scope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            updateState { it.copy(isUploading = true) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                accountApi.removeAvatar().fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState {
                            it.copy(
                                userAvatar = null,
                                isUploading = false,
                                successMessage = text(R.string.settings_avatar_removed)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState {
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
                if (isCurrentOwner(ownerUserId)) updateState { it.copy(isUploading = false) }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
