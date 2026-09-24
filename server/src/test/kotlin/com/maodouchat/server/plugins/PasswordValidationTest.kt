package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 密码合法性：≥[MIN_PASSWORD_LENGTH] 字符且 UTF-8 字节数 ≤72（BCrypt 截断边界），
 * 并拒绝「全同一个字符」。
 *
 * 8.41：此前无最大长度校验，BCrypt 静默截断 72 字节后输入，前 72 字节相同的密码互为等价。
 * G328c：下限从 6 提到 8（审计点名的「弱口令下限」），断言同步更新——注意
 * `seventy two ascii bytes accepted` 与 `multibyte counted in bytes not chars` 的样本
 * 也从「重复字符」换成混合字符，否则会被新的「拒绝全同一字符」规则先挡掉，测不到字节边界。
 */
class PasswordValidationTest {

    @Test
    fun `short password rejected`() {
        assertFalse(isValidPassword("12345"))
        assertFalse(isValidPassword(""))
        assertFalse(isValidPassword("1234567"), "7 位在新下限之下")
    }

    @Test
    fun `eight char password accepted`() {
        assertTrue(isValidPassword("12345678"))
        assertTrue(isValidPassword("abcd1234"))
    }

    @Test
    fun `seventy two ascii bytes accepted`() {
        assertTrue(isValidPassword("a".repeat(71) + "b"), "混合字符，避免被全同一字符规则挡掉")
        assertTrue(isValidPassword("ab".repeat(36)), "72 字节恰好达标")
    }

    @Test
    fun `seventy three ascii bytes rejected`() {
        assertFalse(isValidPassword("ab".repeat(36) + "c"))
    }

    @Test
    fun `multibyte counted in bytes not chars`() {
        // "中" 是 3 字节 UTF-8：24 个汉字 = 72 字节恰好达标，25 个 = 75 字节超限
        assertTrue(isValidPassword("中".repeat(23) + "文"), "24 个汉字 = 72 字节")
        assertFalse(isValidPassword("中".repeat(25)), "25 个汉字 = 75 字节")
    }

    @Test
    fun `single repeated character is rejected regardless of length`() {
        assertFalse(isValidPassword("a".repeat(8)))
        assertFalse(isValidPassword("a".repeat(72)))
        assertFalse(isValidPassword("!!!".repeat(4)))
    }
}
