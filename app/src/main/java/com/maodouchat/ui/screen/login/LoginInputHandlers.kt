package com.maodouchat.ui.screen.login

import kotlinx.coroutines.flow.update

// 输入长度上限：原来住在 LoginViewModel 的 companion 里，随输入处理函数一起搬过来。
private const val MAX_EMAIL_LEN = 254
private const val MAX_PASSWORD_LEN = 128
private const val MAX_NAME_LEN = 50
private const val MAX_CODE_LEN = 8

fun LoginViewModel.onEmailChange(email: String) {
    // 8.61：改邮箱后重置验证码状态/倒计时，避免旧邮箱的「已发送/倒计时」串到新邮箱
    if (_uiState.value.email != email.trim().take(MAX_EMAIL_LEN)) {
        countdownJob?.cancel()
        countdownJob = null
    }
    _uiState.update {
        it.copy(email = email.trim().take(MAX_EMAIL_LEN), errorMessage = null, infoMessage = null, codeSent = false, codeCountdown = 0)
    }
}
fun LoginViewModel.onPasswordChange(password: String) {
    _uiState.update {
        it.copy(
            password = password.take(MAX_PASSWORD_LEN),
            passwordConfirm = "",
            errorMessage = null,
            infoMessage = null
        )
    }
}
fun LoginViewModel.onPasswordConfirmChange(passwordConfirm: String) {
    _uiState.update {
        it.copy(passwordConfirm = passwordConfirm.take(MAX_PASSWORD_LEN), errorMessage = null, infoMessage = null)
    }
}
fun LoginViewModel.onNameChange(name: String) {
    _uiState.update {
        it.copy(name = name.take(MAX_NAME_LEN), errorMessage = null, infoMessage = null)
    }
}
// 0.76：取 8 位——兼容 6 位 TOTP 验证码 + 8 位恢复码（恢复码此前被 take(6) 截断无法登录）
fun LoginViewModel.onTotpCodeChange(value: String) { _uiState.update { it.copy(totpCode = value.filter { ch -> ch.isDigit() }.take(8), errorMessage = null) } }

fun LoginViewModel.onCodeChange(code: String) {
    val digits = code.filter { it.isDigit() }.take(MAX_CODE_LEN)
    _uiState.update {
        it.copy(code = digits, errorMessage = null, infoMessage = null)
    }
}
fun LoginViewModel.onTabSelected(tab: Int) {
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
fun LoginViewModel.togglePasswordVisibility() { _uiState.update { it.copy(passwordVisible = !it.passwordVisible) } }
