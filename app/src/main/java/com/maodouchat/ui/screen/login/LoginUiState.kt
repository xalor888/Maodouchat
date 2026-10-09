package com.maodouchat.ui.screen.login

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
