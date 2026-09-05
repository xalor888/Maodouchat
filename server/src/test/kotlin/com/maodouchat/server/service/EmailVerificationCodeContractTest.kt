package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证码行为契约（B02「可共享 store」迁移门）。
 *
 * `EmailService` 当前为进程内存储；未来迁 Redis/DB 共享存储时，
 * 以下语义必须原样保留（多实例、防重放、防跨用途复用）。
 * 开发模式（无 SMTP 环境变量）下运行，无网络依赖。
 */
class EmailVerificationCodeContractTest {

    @Test
    fun `register and reset codes are isolated`() {
        val email = "iso-${System.nanoTime()}@example.com"
        val registerCode = EmailService.sendVerificationCode(email, EmailService.PURPOSE_REGISTER)
        val resetCode = EmailService.sendVerificationCode(email, EmailService.PURPOSE_RESET)
        // 跨用途复用必须失败，且不得消耗正确用途的码。
        assertFalse(EmailService.verifyCode(email, registerCode, EmailService.PURPOSE_RESET))
        assertFalse(EmailService.verifyCode(email, resetCode, EmailService.PURPOSE_REGISTER))
        assertTrue(EmailService.verifyCode(email, registerCode, EmailService.PURPOSE_REGISTER))
        assertTrue(EmailService.verifyCode(email, resetCode, EmailService.PURPOSE_RESET))
    }

    @Test
    fun `five wrong attempts lock the code even for the right one`() {
        val email = "lock-${System.nanoTime()}@example.com"
        val code = EmailService.sendVerificationCode(email, EmailService.PURPOSE_REGISTER)
        repeat(4) {
            assertFalse(EmailService.verifyCode(email, "000000", EmailService.PURPOSE_REGISTER))
        }
        // 阈值下正确码仍可用。
        val email2 = "lock-ok-${System.nanoTime()}@example.com"
        val code2 = EmailService.sendVerificationCode(email2, EmailService.PURPOSE_REGISTER)
        repeat(4) {
            assertFalse(EmailService.verifyCode(email2, "000000", EmailService.PURPOSE_REGISTER))
        }
        assertTrue(EmailService.verifyCode(email2, code2, EmailService.PURPOSE_REGISTER))
        // 第 5 次错误后锁定：正确码也不再接受。
        assertFalse(EmailService.verifyCode(email, "000000", EmailService.PURPOSE_REGISTER))
        assertFalse(
            EmailService.verifyCode(email, code, EmailService.PURPOSE_REGISTER),
            "locked code must reject even the correct value",
        )
    }

    @Test
    fun `unknown email and blank code never verify`() {
        val email = "ghost-${System.nanoTime()}@example.com"
        assertFalse(EmailService.verifyCode(email, "123456", EmailService.PURPOSE_REGISTER))
        assertFalse(EmailService.verifyCode(email, "", EmailService.PURPOSE_REGISTER))
    }
}
