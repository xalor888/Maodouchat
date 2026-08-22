package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MyQrCodeUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val qrBitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class MyQrCodeViewModel(application: Application) : AndroidViewModel(application) {
    private val userRepo = UserRepository(application.let { (it as com.maodouchat.MaodouchatApp).database.userDao() })
    private val tokenManager = TokenManager.getInstance(application)

    private val _uiState = MutableStateFlow(MyQrCodeUiState())
    val uiState: StateFlow<MyQrCodeUiState> = _uiState.asStateFlow()

    init { reload() }

    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.QR_CODE)) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = getApplication<Application>().getString(R.string.qr_code_disabled)) }
                    return@launch
                }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val token = tokenManager.getToken().orEmpty()
                val userId = tokenManager.getUserId().orEmpty()
                val local = if (userId.isNotBlank()) userRepo.getUserById(userId) else null
                var name = local?.name.orEmpty()
                var avatar = local?.avatar
                if (token.isNotBlank() && userId.isNotBlank()) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = userId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        ApiService.getCurrentUser(liveToken).onSuccess { me ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = userId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@onSuccess
                            }
                            name = me.name
                            avatar = me.avatar
                            _uiState.update { it.copy(userName = me.name, userId = me.id, userAvatar = me.avatar) }
                        }
                    }
                }
                if (_uiState.value.userId.isBlank() && userId.isNotBlank()) {
                    _uiState.update { it.copy(userId = userId) }
                }
                if (_uiState.value.userName.isBlank() && name.isNotBlank()) {
                    _uiState.update { it.copy(userName = name, userAvatar = avatar) }
                }
                val targetUserId = _uiState.value.userId.ifBlank { userId }
                if (targetUserId.isNotBlank()) {
                    val bmp = withContext(Dispatchers.Default) {
                        QrCodeGenerator.generateBitmap(QrCodeGenerator.encodeUserQrPayload(targetUserId), 600)
                    }
                    _uiState.update {
                        it.copy(
                            qrBitmap = bmp,
                            isLoading = false,
                            errorMessage = if (bmp == null) getApplication<Application>().getString(R.string.contacts_qr_generation_failed) else null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = getApplication<Application>().getString(R.string.error_session_expired)
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message
                            ?: getApplication<Application>().getString(R.string.contacts_qr_generation_failed)
                    )
                }
            }
        }
    }
}
