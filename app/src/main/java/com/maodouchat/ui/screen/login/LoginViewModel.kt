package com.maodouchat.ui.screen.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.data.repository.AuthNetworkRepository
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.login.LoginAccess
import com.maodouchat.network.TokenManager
import com.maodouchat.network.toUserFacingMessage
import com.maodouchat.push.PushRegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    internal val tokenManager = TokenManager.getInstance(application)
    internal fun text(id: Int): String = getApplication<Application>().getString(id)

    internal val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    private var logoutJob: kotlinx.coroutines.Job? = null

    init {
        if (tokenManager.isLoggedIn()) {
            viewModelScope.launch { restoreSession() }
        }
    }

    private fun userFacingError(error: Throwable, fallback: String): String =
        error.toUserFacingMessage(
            networkMessage = text(R.string.error_network),
            timeoutMessage = text(R.string.error_timeout),
            invalidResponseMessage = text(R.string.error_invalid_response),
            fallbackMessage = fallback,
        )

    internal var countdownJob: Job? = null

    /**
     * 发送验证码 — 立即标记 isCodeSending 避免连点
     * 注册 tab → purpose=register；找回密码 tab → purpose=reset
     */
    fun sendVerificationCode() {
        val email = _uiState.value.email
        // 8.61：改用 Patterns 校验（此前 contains("@") 让 "a@" 之类通过，错误信息不明确）
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_invalid_email)) }
            return
        }
        // 同步设置 isCodeSending 防连点
        if (_uiState.value.isCodeSending || _uiState.value.codeCountdown > 0) return
        _uiState.update { it.copy(isCodeSending = true, errorMessage = null, infoMessage = null) }
        val purpose = if (_uiState.value.selectedTab == 2) "reset" else "register"

        viewModelScope.launch {
            try {
                val result = AuthNetworkRepository().sendVerificationCode(email, purpose)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isCodeSending = false, codeSent = true, codeCountdown = 60) }
                        // 倒计时协程可取消
                        countdownJob?.cancel()
                        countdownJob = launch {
                            for (i in 60 downTo 1) {
                                if (!isActive) return@launch
                                _uiState.update { it.copy(codeCountdown = i) }
                                kotlinx.coroutines.delay(1000)
                            }
                            _uiState.update { it.copy(codeCountdown = 0) }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isCodeSending = false,
                                errorMessage = userFacingError(error, text(R.string.error_send_code_failed)),
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isCodeSending = false) }
                throw error
            } catch (error: Exception) {
                // 兜底：未预期异常（如 IOException、NPE）也会重置 isCodeSending，避免 spinner 永久卡住
                _uiState.update {
                    it.copy(
                        isCodeSending = false,
                        errorMessage = userFacingError(error, text(R.string.error_send_code_failed)),
                    )
                }
            }
        }
    }

    /**
     * 提交（登录 / 注册 / 重置密码）
     */
    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return

        if (state.email.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_enter_email)) }; return }
        // 8.61：Patterns 校验（此前 contains("@") 让 "a@" 之类提交后由服务端拒绝、错误不明确）
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(state.email).matches()) { _uiState.update { it.copy(errorMessage = text(R.string.error_invalid_email)) }; return }
        if (state.password.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_enter_password)) }; return }
        if (state.password.length < 6) { _uiState.update { it.copy(errorMessage = text(R.string.error_password_min_length)) }; return }

        when (state.selectedTab) {
            1 -> {
                if (!state.codeSent) { _uiState.update { it.copy(errorMessage = text(R.string.login_require_code_first)) }; return }
                if (state.name.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_enter_username)) }; return }
                if (state.passwordConfirm.isBlank() || state.passwordConfirm != state.password) {
                    _uiState.update { it.copy(errorMessage = text(R.string.login_password_confirm_mismatch)) }
                    return
                }
                if (state.code.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_enter_verification_code)) }; return }
            }
            2 -> {
                if (!state.codeSent) { _uiState.update { it.copy(errorMessage = text(R.string.login_require_code_first)) }; return }
                if (state.code.isBlank()) { _uiState.update { it.copy(errorMessage = text(R.string.error_enter_verification_code)) }; return }
            }
        }

        // 同步置位 isLoading，确保入口守卫 `if (state.isLoading) return` 在同帧连点下也生效
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }

        if (state.selectedTab == 2) {
            viewModelScope.launch {
                _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
                try {
                    AuthNetworkRepository().resetPassword(state.email, state.code, state.password).fold(
                        onSuccess = {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    selectedTab = 0,
                                    code = "",
                                    password = "",
                                    infoMessage = text(R.string.login_reset_success),
                                    errorMessage = null
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = userFacingError(error, text(R.string.error_operation_failed)),
                                )
                            }
                        }
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    _uiState.update { it.copy(isLoading = false) }
                    throw error
                } catch (error: Exception) {
                    // 兜底：未预期异常也会重置 isLoading，避免 spinner 永久卡住
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = userFacingError(error, text(R.string.error_operation_failed)),
                        )
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
            try {
                val result = if (state.selectedTab == 0) {
                    AuthNetworkRepository().login(state.email, state.password, state.totpCode)
                } else {
                    AuthNetworkRepository().register(state.name, state.email, state.password, state.code)
                }

                result.fold(
                    onSuccess = { auth ->
                        if (auth.requiresTotp) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    requiresTotp = true,
                                    errorMessage = text(R.string.login_totp_required),
                                    infoMessage = text(R.string.login_totp_hint)
                                )
                            }
                            return@launch
                        }
                        if (auth.token.isBlank() || auth.userId.isBlank()) {
                            _uiState.update {
                                it.copy(isLoading = false, errorMessage = text(R.string.error_operation_failed))
                            }
                            return@launch
                        }
                        LoginAccess.secureSessionManager.purgeIfAccountChanged(auth.userId)
                        // Token 持久化失败 = 下次冷启动丢失登录态，直接暴露给用户
                        // 8.49：saveAuthSession 内部对 EncryptedSharedPreferences commit()（同步
                        // fsync），移到 IO 调度器避免主线程卡顿
                        val sessionSaved = withContext(Dispatchers.IO) {
                            tokenManager.saveAuthSession(
                                token = auth.token,
                                refreshToken = auth.refreshToken,
                                userId = auth.userId,
                                accessTokenExpiresAt = auth.expiresAt,
                                refreshTokenExpiresAt = auth.refreshExpiresAt
                            )
                        }
                        if (!sessionSaved) {
                            _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_persist_failed)) }
                            return@launch
                        }
                        LoginAccess.notificationCenter.refreshAccount()
                        // Server upload may retry later, but the account-scoped local store must
                        // be ready before navigation can expose any cipher entry point.
                        val localCryptoReady = withContext(Dispatchers.IO) {
                            var localReady = false
                            for (attempt in 0 until 3) {
                                try {
                                    LoginAccess.signalProtocol.initialize(auth.token, auth.userId)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("LoginViewModel", "Signal initialize attempt ${attempt + 1} failed", error)
                                }
                                localReady = LoginAccess.signalProtocol.isLocalStoreReadyFor(auth.userId)
                                if (LoginAccess.signalProtocol.isInitializedFor(auth.userId)) break
                                if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
                            }
                            if (!localReady) {
                                android.util.Log.w("LoginViewModel", "Signal local key restore failed after retries")
                            }
                            localReady
                        }
                        if (!localCryptoReady) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isLoggedIn = false,
                                    errorMessage = text(R.string.security_e2ee_not_ready),
                                )
                            }
                            return@launch
                        }
                        // 8.49 修复：Signal 就绪后置位登录成功——后置步骤（推送注册/提醒调度/
                        // 附件对账）失败不再吞掉 isLoggedIn，把已生效的会话留在登录页
                        _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                        // 8.49：后置步骤各自 best-effort，异常不再中断（isLoggedIn 已置位）
                        runCatching { PushRegistrationManager.refreshRegistration(getApplication()) }
                            .onFailure { android.util.Log.w("LoginViewModel", "push registration after login failed", it) }
                        // 9.3xx：登录成功后按设置恢复推送保活（Ideaura 式）
                        runCatching { com.maodouchat.push.PushKeepAlive.ensureForUser(getApplication()) }
                            .onFailure { android.util.Log.w("LoginViewModel", "push keepalive start failed", it) }
                        runCatching { AiTaskReminderScheduler.ensureScheduled(getApplication()) }
                            .onFailure { android.util.Log.w("LoginViewModel", "ai task reminder scheduling failed", it) }
                        runCatching { com.maodouchat.attachment.AttachmentTransferCoordinator.reconcile(getApplication()) }
                            .onFailure { android.util.Log.w("LoginViewModel", "attachment reconcile after login failed", it) }
                        runCatching {
                            com.maodouchat.crypto.SenderKeyRetryWorkScheduler.ensureScheduled(getApplication())
                            LoginAccess.senderKeyRetryManager.processDueTasks()
                        }.onFailure { android.util.Log.w("LoginViewModel", "sender-key retry resume after login failed", it) }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = userFacingError(error, text(R.string.error_operation_failed)),
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Clear spinner always; session may or may not have been persisted depending on cancel point.
                _uiState.update { it.copy(isLoading = false) }
                throw error
            } catch (error: Exception) {
                // 兜底：未预期异常也会重置 isLoading，避免 spinner 永久卡住
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = userFacingError(error, text(R.string.error_operation_failed)),
                    )
                }
            }
        }
    }

    fun logout() {
        // 8.61：重入守卫——连点登出/与 NavGraph tokenExpired 并发 purge 只执行一次
        if (logoutJob?.isActive == true) return
        logoutJob = viewModelScope.launch {
            LoginAccess.secureSessionManager.purgeLocalSession(
                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                    com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                )
            )
            _uiState.update { LoginUiState() }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }
}
