from pathlib import Path

content = '''package com.maodouchat.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val userStatus: String = "",
    val isLoggedOut: Boolean = false,
    val isEditing: Boolean = false,
    val editName: String = "",
    val showPrivacyDialog: Boolean = false,
    val showOnline: Boolean = true,
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC",
    val isUploading: Boolean = false,
    val isSaving: Boolean = false,
    val isSavingPrivacy: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenManager = TokenManager.getInstance(application)
    private val app = application as MaodouchatApp

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val visibilityOptions = listOf(
        "PUBLIC" to "公开",
        "CONTACTS" to "联系人",
        "PRIVATE" to "仅自己"
    )

    init {
        loadUserInfo()
        loadPrivacy()
    }

    private fun normalizeVisibility(value: String): String {
        return if (value in visibilityOptions.map { it.first }) value else "PUBLIC"
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            val token = tokenManager.getToken() ?: return@launch
            val result = ApiService.getCurrentUser(token)
            result.onSuccess { me ->
                _uiState.update {
                    it.copy(userName = me.name, userId = me.id, userAvatar = me.avatar, userStatus = me.status, editName = me.name)
                }
            }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message ?: "用户信息加载失败") }
            }
            if (_uiState.value.userName.isBlank()) {
                _uiState.update { it.copy(userName = "用户", userId = tokenManager.getUserId() ?: "", editName = "用户") }
            }
        }
    }

    private fun loadPrivacy() {
        viewModelScope.launch {
            val token = tokenManager.getToken() ?: return@launch
            ApiService.getPrivacy(token).onSuccess { privacy ->
                _uiState.update {
                    it.copy(
                        showOnline = privacy.showOnline,
                        searchable = privacy.searchable,
                        defaultPostVisibility = normalizeVisibility(privacy.defaultPostVisibility)
                    )
                }
            }
        }
    }

    fun onEditNameChange(name: String) { _uiState.update { it.copy(editName = name.take(30)) } }
    fun startEditing() { _uiState.update { it.copy(isEditing = true, editName = it.userName) } }
    fun cancelEditing() { _uiState.update { it.copy(isEditing = false) } }

    fun saveProfile() {
        val name = _uiState.value.editName.trim()
        if (name.isBlank()) { _uiState.update { it.copy(errorMessage = "昵称不能为空") }; return }
        viewModelScope.launch {
            val token = tokenManager.getToken()
            if (token == null) { _uiState.update { it.copy(errorMessage = "登录已过期，请重新登录") }; return@launch }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            ApiService.updateProfile(token, name = name).fold(
                onSuccess = { user ->
                    _uiState.update { it.copy(userName = user.name, userAvatar = user.avatar, userStatus = user.status, editName = user.name, isEditing = false, isSaving = false, successMessage = "修改成功") }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = "修改失败: ${error.message}") }
                }
            )
        }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            val base64 = ImagePicker.uriToBase64(getApplication(), uri, maxWidth = 400, quality = 80)
            if (base64 != null) {
                val token = tokenManager.getToken()
                if (token == null) { _uiState.update { it.copy(isUploading = false, errorMessage = "登录已过期，请重新登录") }; return@launch }
                ApiService.uploadAvatar(token, base64).fold(
                    onSuccess = { url -> _uiState.update { it.copy(userAvatar = url, isUploading = false, successMessage = "头像更新成功") } },
                    onFailure = { error -> _uiState.update { it.copy(isUploading = false, errorMessage = "上传失败: ${error.message}") } }
                )
            } else { _uiState.update { it.copy(isUploading = false, errorMessage = "图片处理失败") } }
        }
    }

    fun openPrivacy() { _uiState.update { it.copy(showPrivacyDialog = true) } }
    fun closePrivacy() { _uiState.update { it.copy(showPrivacyDialog = false) } }
    fun onShowOnlineChange(v: Boolean) { _uiState.update { it.copy(showOnline = v) } }
    fun onSearchableChange(v: Boolean) { _uiState.update { it.copy(searchable = v) } }
    fun onDefaultVisibilityChange(v: String) { _uiState.update { it.copy(defaultPostVisibility = normalizeVisibility(v)) } }

    fun savePrivacy() {
        val previous = _uiState.value
        viewModelScope.launch {
            val token = tokenManager.getToken()
            if (token == null) { _uiState.update { it.copy(errorMessage = "登录已过期，请重新登录") }; return@launch }
            _uiState.update { it.copy(isSavingPrivacy = true, errorMessage = null) }
            ApiService.updatePrivacy(
                token, showOnline = previous.showOnline, searchable = previous.searchable,
                defaultPostVisibility = previous.defaultPostVisibility
            ).fold(
                onSuccess = { privacy ->
                    _uiState.update {
                        it.copy(showOnline = privacy.showOnline, searchable = privacy.searchable,
                               defaultPostVisibility = normalizeVisibility(privacy.defaultPostVisibility),
                               showPrivacyDialog = false, isSavingPrivacy = false, successMessage = "隐私设置已保存")
                    }
                },
                onFailure = { error ->
                    loadPrivacy()
                    _uiState.update { it.copy(isSavingPrivacy = false, errorMessage = error.message ?: "隐私设置保存失败") }
                }
            )
        }
    }

    fun showComingSoon(title: String) { _uiState.update { it.copy(successMessage = "$title 功能正在完善中") } }
    fun clearSuccessMessage() { _uiState.update { it.copy(successMessage = null) } }
    fun clearErrorMessage() { _uiState.update { it.copy(errorMessage = null) } }

    fun logout() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                com.maodouchat.network.WebSocketClient.disconnect()
                app.secureSessionManager.purgeLocalSession()
            }
            _uiState.update { it.copy(isLoggedOut = true) }
        }
    }
}
'''

p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/settings/SettingsViewModel.kt')
p.write_text(content)
print('done')
