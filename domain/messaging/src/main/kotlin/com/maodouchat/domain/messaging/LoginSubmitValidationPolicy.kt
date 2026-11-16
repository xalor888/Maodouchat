package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LoginUiState

// 登录提交校验链：按序短路，返回首个失败原因；邮箱格式由调用方注入 predicate
//（生产侧传 Patterns.EMAIL_ADDRESS，语义不变）。
enum class LoginValidationFailure {
    EmailBlank,
    EmailInvalid,
    PasswordBlank,
    PasswordTooShort,
    CodeNotSent,
    NameBlank,
    PasswordConfirmMismatch,
    CodeBlank
}

object LoginSubmitValidationPolicy {

    // 仅邮箱两支：发码守卫与提交校验共用（发码侧空白与格式非法映射到同一文案）。
    fun validateEmail(email: String, isValidEmail: (String) -> Boolean): LoginValidationFailure? {
        if (email.isBlank()) return LoginValidationFailure.EmailBlank
        if (!isValidEmail(email)) return LoginValidationFailure.EmailInvalid
        return null
    }

    fun validate(state: LoginUiState, isValidEmail: (String) -> Boolean): LoginValidationFailure? {
        validateEmail(state.email, isValidEmail)?.let { return it }
        if (state.password.isBlank()) return LoginValidationFailure.PasswordBlank
        if (state.password.length < 6) return LoginValidationFailure.PasswordTooShort
        when (state.selectedTab) {
            1 -> {
                if (!state.codeSent) return LoginValidationFailure.CodeNotSent
                if (state.name.isBlank()) return LoginValidationFailure.NameBlank
                if (state.passwordConfirm.isBlank() || state.passwordConfirm != state.password) {
                    return LoginValidationFailure.PasswordConfirmMismatch
                }
                if (state.code.isBlank()) return LoginValidationFailure.CodeBlank
            }
            2 -> {
                if (!state.codeSent) return LoginValidationFailure.CodeNotSent
                if (state.code.isBlank()) return LoginValidationFailure.CodeBlank
            }
        }
        return null
    }
}
