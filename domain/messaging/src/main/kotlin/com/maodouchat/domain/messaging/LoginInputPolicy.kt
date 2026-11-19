package com.maodouchat.domain.messaging

// 登录页输入归一化与 Tab 切换：LoginUiState -> LoginUiState 的纯状态转移，
// 副作用（倒计时取消、R.string、网络）留在 app 侧薄包装里。
object LoginInputPolicy {

    const val MAX_EMAIL_LEN = 254
    const val MAX_PASSWORD_LEN = 128
    const val MAX_NAME_LEN = 50
    const val MAX_CODE_LEN = 8

    fun normalizeEmail(email: String): String = email.trim().take(MAX_EMAIL_LEN)

    fun applyEmailChange(state: LoginUiState, email: String): LoginUiState =
        state.copy(
            email = normalizeEmail(email),
            errorMessage = null,
            infoMessage = null,
            codeSent = false,
            codeCountdown = 0
        )

    fun applyPasswordChange(state: LoginUiState, password: String): LoginUiState =
        state.copy(
            password = password.take(MAX_PASSWORD_LEN),
            // 每敲一键都清空确认密码，原行为照抄。
            passwordConfirm = "",
            errorMessage = null,
            infoMessage = null
        )

    fun applyPasswordConfirmChange(state: LoginUiState, passwordConfirm: String): LoginUiState =
        state.copy(
            passwordConfirm = passwordConfirm.take(MAX_PASSWORD_LEN),
            errorMessage = null,
            infoMessage = null
        )

    fun applyNameChange(state: LoginUiState, name: String): LoginUiState =
        state.copy(name = name.take(MAX_NAME_LEN), errorMessage = null, infoMessage = null)

    // 0.76：取 8 位——兼容 6 位 TOTP 验证码 + 8 位恢复码（恢复码此前被 take(6) 截断无法登录）。
    fun applyTotpCodeChange(state: LoginUiState, value: String): LoginUiState =
        state.copy(totpCode = value.filter { it.isDigit() }.take(MAX_CODE_LEN), errorMessage = null)

    fun applyCodeChange(state: LoginUiState, code: String): LoginUiState =
        state.copy(
            code = code.filter { it.isDigit() }.take(MAX_CODE_LEN),
            errorMessage = null,
            infoMessage = null
        )

    fun applyTabSelected(state: LoginUiState, tab: Int): LoginUiState =
        state.copy(
            selectedTab = tab.coerceIn(0, 2),
            errorMessage = null,
            infoMessage = null,
            codeSent = false,
            // 注意：用原始 tab 参数（非 coerce 后的值）决定清理项，原行为照抄。
            code = if (tab == 0) "" else state.code,
            passwordConfirm = if (tab == 1) state.passwordConfirm else "",
            // 8.61：切回登录 tab 清残留 TOTP 码（服务端未启用 TOTP 时忽略，但避免串带到下次登录）。
            totpCode = if (tab == 0) "" else state.totpCode,
            // 8.47：切 tab 清 requiresTotp 标志——否则 TOTP 提示后切到注册/找回再切回，TOTP 输入框仍残留显示。
            requiresTotp = false
        )

    fun togglePasswordVisibility(state: LoginUiState): LoginUiState =
        state.copy(passwordVisible = !state.passwordVisible)
}
