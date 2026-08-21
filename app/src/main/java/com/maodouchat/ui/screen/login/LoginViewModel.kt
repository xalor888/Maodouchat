package com.maodouchat.ui.screen.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
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

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val name: String = "",
    val code: String = "",
    val totpCode: String = "",
    val requiresTotp: Boolean = false,
    val selectedTab: Int = 0, // 0 login / 1 register / 2 reset
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isCodeSending: Boolean = false,
    val codeSent: Boolean = false,
    val codeCountdown: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isLoggedIn: Boolean = false
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenManager = TokenManager.getInstance(application)
    private val app = application as MaodouchatApp
    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    private var logoutJob: kotlinx.coroutines.Job? = null

    init {
        if (tokenManager.isLoggedIn()) {
            // Restored session: ensure Signal store is loaded before user opens chats.
            // App-level cold-start also does this; this covers LoginViewModel-first paths.
            // 8.49 修复：isLoggedIn 改为初始化完成后置位（与新鲜登录路径一致）——此前同步
            // 置 true，密钥库加载慢时用户先进聊天页看到解密失败占位
            viewModelScope.launch {
                val userId = tokenManager.getUserId()
                val token = tokenManager.getToken()
                if (!userId.isNullOrBlank() && !app.signalProtocol.isInitializedFor(userId)) {
                    try {
                        app.signalProtocol.initialize(token, userId)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("LoginViewModel", "Signal restore for restored session failed", error)
                    }
                }
                _uiState.update { it.copy(isLoggedIn = true, requiresTotp = false, totpCode = "") }
            }
        }
    }

    fun onEmailChange(email: String) {
        // 8.61：改邮箱后重置验证码状态/倒计时，避免旧邮箱的「已发送/倒计时」串到新邮箱
        if (_uiState.value.email != email.trim().take(MAX_EMAIL_LEN)) {
            countdownJob?.cancel()
            countdownJob = null
        }
        _uiState.update {
            it.copy(email = email.trim().take(MAX_EMAIL_LEN), errorMessage = null, infoMessage = null, codeSent = false, codeCountdown = 0)
        }
    }
    fun onPasswordChange(password: String) {
        _uiState.update {
            it.copy(
                password = password.take(MAX_PASSWORD_LEN),
                passwordConfirm = "",
                errorMessage = null,
                infoMessage = null
            )
        }
    }
    fun onPasswordConfirmChange(passwordConfirm: String) {
        _uiState.update {
            it.copy(passwordConfirm = passwordConfirm.take(MAX_PASSWORD_LEN), errorMessage = null, infoMessage = null)
        }
    }
    fun onNameChange(name: String) {
        _uiState.update {
            it.copy(name = name.take(MAX_NAME_LEN), errorMessage = null, infoMessage = null)
        }
    }
    // 0.76：取 8 位——兼容 6 位 TOTP 验证码 + 8 位恢复码（恢复码此前被 take(6) 截断无法登录）
    fun onTotpCodeChange(value: String) { _uiState.update { it.copy(totpCode = value.filter { ch -> ch.isDigit() }.take(8), errorMessage = null) } }

    fun onCodeChange(code: String) {
        val digits = code.filter { it.isDigit() }.take(MAX_CODE_LEN)
        _uiState.update {
            it.copy(code = digits, errorMessage = null, infoMessage = null)
        }
    }
    fun onTabSelected(tab: Int) {
        _uiState.update {
            it.copy(
                selectedTab = tab.coerceIn(0, 2),
                errorMessage = null,
                infoMessage = null,
                codeSent = false,
                code = if (tab == 0) "" else it.code,
                passwordConfirm = if (tab == 1) it.passwordConfirm else "",
                // 8.61：切回登录 tab 清残留 TOTP 码（服务端未启用 TOTP 时忽略，但避免串带到下次登录）
                totpCode = if (tab == 0) "" else it.totpCode,
                // 8.47：切 tab 清 requiresTotp 标志——否则 TOTP 提示后切到注册/找回再切回，
                // TOTP 输入框仍残留显示
                requiresTotp = false
            )
        }
    }
    fun togglePasswordVisibility() { _uiState.update { it.copy(passwordVisible = !it.passwordVisible) } }

    private var countdownJob: Job? = null

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
                val result = ApiService.sendVerificationCode(email, purpose)
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
                        _uiState.update { it.copy(isCodeSending = false, errorMessage = error.message ?: text(R.string.error_send_code_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isCodeSending = false) }
                throw error
            } catch (error: Exception) {
                // 兜底：未预期异常（如 IOException、NPE）也会重置 isCodeSending，避免 spinner 永久卡住
                _uiState.update { it.copy(isCodeSending = false, errorMessage = error.message ?: text(R.string.error_send_code_failed)) }
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
                    ApiService.resetPassword(state.email, state.code, state.password).fold(
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
                                it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.error_operation_failed))
                            }
                        }
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    _uiState.update { it.copy(isLoading = false) }
                    throw error
                } catch (error: Exception) {
                    // 兜底：未预期异常也会重置 isLoading，避免 spinner 永久卡住
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.error_operation_failed)) }
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
            try {
                val result = if (state.selectedTab == 0) {
                    ApiService.login(state.email, state.password, state.totpCode)
                } else {
                    ApiService.registerWithCode(state.name, state.email, state.password, state.code)
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
                        app.secureSessionManager.purgeIfAccountChanged(auth.userId)
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
                        app.notificationCenter.refreshAccount()
                        // 密钥初始化失败不阻断登录（Signal 后台密钥上传可以稍后重试），但必须显式记录并提示
                        withContext(Dispatchers.IO) {
                            var ready = false
                            repeat(3) { attempt ->
                                ready = try {
                                    app.signalProtocol.initialize(auth.token, auth.userId)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("LoginViewModel", "Signal initialize attempt ${attempt + 1} failed", error)
                                    false
                                }
                                if (ready || attempt == 2) return@repeat
                                kotlinx.coroutines.delay(500L * (attempt + 1))
                            }
                            if (!ready) {
                                android.util.Log.w("LoginViewModel", "Signal keys initialization/upload failed after retries — continuing login with degraded E2EE")
                            }
                        }
                        // 8.49 修复：Signal 就绪后置位登录成功——后置步骤（推送注册/提醒调度/
                        // 附件对账）失败不再吞掉 isLoggedIn，把已生效的会话留在登录页
                        _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                        // 8.49：后置步骤各自 best-effort，异常不再中断（isLoggedIn 已置位）
                        runCatching { PushRegistrationManager.refreshRegistration(app) }
                            .onFailure { android.util.Log.w("LoginViewModel", "push registration after login failed", it) }
                        // 9.3xx：登录成功后按设置恢复推送保活（Ideaura 式）
                        runCatching { com.maodouchat.push.PushKeepAlive.ensureForUser(app) }
                            .onFailure { android.util.Log.w("LoginViewModel", "push keepalive start failed", it) }
                        runCatching { AiTaskReminderScheduler.ensureScheduled(app) }
                            .onFailure { android.util.Log.w("LoginViewModel", "ai task reminder scheduling failed", it) }
                        runCatching { com.maodouchat.attachment.AttachmentTransferCoordinator.reconcile(app) }
                            .onFailure { android.util.Log.w("LoginViewModel", "attachment reconcile after login failed", it) }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.error_operation_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Clear spinner always; session may or may not have been persisted depending on cancel point.
                _uiState.update { it.copy(isLoading = false) }
                throw error
            } catch (error: Exception) {
                // 兜底：未预期异常也会重置 isLoading，避免 spinner 永久卡住
                _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.error_operation_failed)) }
            }
        }
    }

    fun logout() {
        // 8.61：重入守卫——连点登出/与 NavGraph tokenExpired 并发 purge 只执行一次
        if (logoutJob?.isActive == true) return
        logoutJob = viewModelScope.launch {
            app.secureSessionManager.purgeLocalSession()
            _uiState.update { LoginUiState() }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_EMAIL_LEN = 254
        private const val MAX_PASSWORD_LEN = 128
        private const val MAX_NAME_LEN = 50
        private const val MAX_CODE_LEN = 8
    }
}
