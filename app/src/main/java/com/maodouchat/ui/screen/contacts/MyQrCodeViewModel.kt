package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.data.repository.UserNetworkRepository

data class MyQrCodeUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val qrBitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class MyQrCodeViewModel(application: Application) : AndroidViewModel(application) {
    // G72：经 AppDatabase.getInstance(context) 这个中立入口取库，不再 import/转型 MaodouchatApp。
    // 装配点只剩 data 层的单例访问，ui 层从此不认识应用类（ClientArchitectureTest 的名单因此能收紧）。
    private val userRepo = UserRepository(com.maodouchat.data.local.AppDatabase.getInstance(application).userDao())

    private val _uiState = MutableStateFlow(MyQrCodeUiState())
    val uiState: StateFlow<MyQrCodeUiState> = _uiState.asStateFlow()

    init { reload() }

    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                // 「有没有会话」与「当前是谁」都是会话态：读会话层，不读凭据存储（G332）。
                val token0 = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                val userId0 = com.maodouchat.session.CurrentSession.ownerUserId()
                // G72：加载判定下沉到纯策略（可单测），这里只编排副作用。
                val precheck = MyQrCodeLoadPolicy.plan(
                    qrEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.QR_CODE),
                    token = token0,
                    userId = userId0,
                    local = null,
                    remote = null,
                )
                if (precheck is MyQrCodeLoadPolicy.Decision.Reject) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = when (precheck.reason) {
                                MyQrCodeLoadPolicy.RejectReason.DISABLED ->
                                    getApplication<Application>().getString(R.string.qr_code_disabled)
                                MyQrCodeLoadPolicy.RejectReason.NO_SESSION ->
                                    getApplication<Application>().getString(R.string.error_session_expired)
                            },
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val token = token0
                val userId = userId0
                val local = if (userId.isNotBlank()) userRepo.getUserById(userId) else null
                var name = local?.name.orEmpty()
                var avatar = local?.avatar
                if (token.isNotBlank() && userId.isNotBlank()) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = userId,
                    )
                    ) {
                        UserNetworkRepository().currentUser().onSuccess { me ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = userId,
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
                    val outcome = MyQrCodeLoadPolicy.finalize(bmp)
                    _uiState.update {
                        it.copy(
                            qrBitmap = outcome.bitmap,
                            isLoading = false,
                            errorMessage = if (outcome.success) {
                                null
                            } else {
                                getApplication<Application>().getString(R.string.contacts_qr_generation_failed)
                            },
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
