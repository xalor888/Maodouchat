package com.maodouchat.ui.screen.login

import com.maodouchat.domain.messaging.LoginInputPolicy
import kotlinx.coroutines.flow.update

// 输入处理薄包装：纯状态转移在 LoginInputPolicy，副作用（倒计时取消）留在这里。
fun LoginViewModel.onEmailChange(email: String) {
    // 8.61：改邮箱后重置验证码状态/倒计时，避免旧邮箱的「已发送/倒计时」串到新邮箱
    if (_uiState.value.email != LoginInputPolicy.normalizeEmail(email)) {
        countdownJob?.cancel()
        countdownJob = null
    }
    _uiState.update { LoginInputPolicy.applyEmailChange(it, email) }
}

fun LoginViewModel.onPasswordChange(password: String) {
    _uiState.update { LoginInputPolicy.applyPasswordChange(it, password) }
}

fun LoginViewModel.onPasswordConfirmChange(passwordConfirm: String) {
    _uiState.update { LoginInputPolicy.applyPasswordConfirmChange(it, passwordConfirm) }
}

fun LoginViewModel.onNameChange(name: String) {
    _uiState.update { LoginInputPolicy.applyNameChange(it, name) }
}

fun LoginViewModel.onTotpCodeChange(value: String) {
    _uiState.update { LoginInputPolicy.applyTotpCodeChange(it, value) }
}

fun LoginViewModel.onCodeChange(code: String) {
    _uiState.update { LoginInputPolicy.applyCodeChange(it, code) }
}

fun LoginViewModel.onTabSelected(tab: Int) {
    _uiState.update { LoginInputPolicy.applyTabSelected(it, tab) }
}

fun LoginViewModel.togglePasswordVisibility() {
    _uiState.update { LoginInputPolicy.togglePasswordVisibility(it) }
}
