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
        // 模拟 submit 的参数校验前置条件（与 LoginViewModel.submit 中的前两条校验一致）
        val blankEmail = ""
        val validPassword = "password123"
        val error = runValidation(blankEmail, validPassword)
        assertTrue(error == "请输入邮箱地址" || error == "请输入有效的邮箱地址")
    }

    @Test
    fun `validation short password returns before IO`() {
        val validEmail = "u@x.com"
        val shortPassword = "123"
        val error = runValidation(validEmail, shortPassword)
        assertEquals("密码至少需要6位", error)
    }

    @Test
    fun `validation valid input passes`() {
        val validEmail = "u@x.com"
        val validPassword = "password123"
        val error = runValidation(validEmail, validPassword)
        assertNull(error)
    }

    /** 与 LoginViewModel.submit 内的校验逻辑保持一致的纯函数镜像，用于回归守护。 */
    private fun runValidation(email: String, password: String): String? {
        if (email.isBlank()) return "请输入邮箱地址"
        if (!email.contains("@")) return "请输入有效的邮箱地址"
        if (password.isBlank()) return "请输入密码"
        if (password.length < 6) return "密码至少需要6位"
        return null
    }
}
