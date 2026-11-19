package com.maodouchat.login

import com.maodouchat.domain.messaging.LoginUiState
import com.maodouchat.domain.messaging.LoginSubmitValidationPolicy
import com.maodouchat.domain.messaging.LoginValidationFailure
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * LoginViewModel 的纯 JVM 状态机单测（AndroidViewModel 依赖在 JVM 上跑不了，
 * 这里测试其暴露的纯函数/状态计算逻辑；真正的 submit/sendVerificationCode 需要在 Robolectric
 * 或 Android instrumented test 中验证）。
 *
 * 注意：LoginViewModel.submit() 里有直接依赖 ApiService的IO 调用，且构造函数需要
 * Application context，故不做行为验证；这里只验证状态转换的纯函数部分。
 */
class LoginViewModelTest {

    @Test
    fun `initial state is blank and not logged in`() {
        val initial = LoginUiState()
        assertEquals("", initial.email)
        assertEquals("", initial.password)
        assertEquals(0, initial.selectedTab)
        assertFalse(initial.isLoading)
        assertFalse(initial.isLoggedIn)
        assertNull(initial.errorMessage)
    }

    @Test
    fun `validation blank email returns before IO`() {
        val error = runValidation("", "password123")
        assertEquals("请输入邮箱地址", error)
    }

    @Test
    fun `validation incomplete email returns before IO`() {
        val error = runValidation("a@", "password123")
        assertEquals("请输入有效的邮箱地址", error)
    }

    @Test
    fun `validation short password returns before IO`() {
        val error = runValidation("u@x.com", "123")
        assertEquals("密码至少需要 6 位", error)
    }

    @Test
    fun `validation valid input passes`() {
        val error = runValidation("u@x.com", "password123")
        assertNull(error)
    }

    /**
     * 校验逻辑在 LoginSubmitValidationPolicy；这里只把失败原因映射回文案断言。
     * 邮箱规则对齐 Patterns.EMAIL_ADDRESS 的最低要求：local@host.tld。
     */
    private fun runValidation(email: String, password: String): String? {
        val state = LoginUiState(email = email, password = password)
        return when (LoginSubmitValidationPolicy.validate(state, ::isPlausibleEmail)) {
            null -> null
            LoginValidationFailure.EmailBlank -> "请输入邮箱地址"
            LoginValidationFailure.EmailInvalid -> "请输入有效的邮箱地址"
            LoginValidationFailure.PasswordBlank -> "请输入密码"
            LoginValidationFailure.PasswordTooShort -> "密码至少需要 6 位"
            LoginValidationFailure.CodeNotSent -> "请先获取并填写验证码"
            LoginValidationFailure.NameBlank -> "请输入用户名"
            LoginValidationFailure.PasswordConfirmMismatch -> "两次输入的密码不一致"
            LoginValidationFailure.CodeBlank -> "请输入验证码"
        }
    }

    private fun isPlausibleEmail(email: String): Boolean {
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) return false
        val domain = email.substring(at + 1)
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }
}
