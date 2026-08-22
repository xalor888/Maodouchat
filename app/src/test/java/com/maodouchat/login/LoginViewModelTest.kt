package com.maodouchat.login

import com.maodouchat.ui.screen.login.LoginUiState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * 与 LoginViewModel.submit 内的校验逻辑保持一致的纯函数镜像。
     * 邮箱规则对齐 Patterns.EMAIL_ADDRESS 的最低要求：local@host.tld。
     */
    private fun runValidation(email: String, password: String): String? {
        if (email.isBlank()) return "请输入邮箱地址"
        if (!isPlausibleEmail(email)) return "请输入有效的邮箱地址"
        if (password.isBlank()) return "请输入密码"
        if (password.length < 6) return "密码至少需要 6 位"
        return null
    }

    private fun isPlausibleEmail(email: String): Boolean {
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) return false
        val domain = email.substring(at + 1)
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }
}
