package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 密码合法性：≥6 字符且 UTF-8 字节数 ≤72（BCrypt 截断边界）。
 * 8.41：此前无最大长度校验，BCrypt 静默截断 72 字节后输入，前 72 字节相同的密码互为等价。
 */
class PasswordValidationTest {

    @Test
    fun `short password rejected`() {
        assertFalse(isValidPassword("12345"))
        assertFalse(isValidPassword(""))
    }

    @Test
    fun `six char password accepted`() {
        assertTrue(isValidPassword("123456"))
    }

    @Test
    fun `seventy two ascii bytes accepted`() {
        assertTrue(isValidPassword("a".repeat(72)))
    }

    @Test
    fun `seventy three ascii bytes rejected`() {
        assertFalse(isValidPassword("a".repeat(73)))
    }

    @Test
    fun `multibyte counted in bytes not chars`() {
        // "中" 是 3 字节 UTF-8：24 个汉字 = 72 字节恰好达标，25 个 = 75 字节超限
        assertTrue(isValidPassword("中".repeat(24)))
        assertFalse(isValidPassword("中".repeat(25)))
    }
}
