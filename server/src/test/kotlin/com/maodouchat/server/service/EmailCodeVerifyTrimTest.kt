package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailCodeVerifyTrimTest {

    @Test
    fun `verifyCode accepts surrounding whitespace`() {
        val email = "trim-${System.nanoTime()}@example.com"
        val code = EmailService.sendVerificationCode(email, EmailService.PURPOSE_REGISTER)
        assertTrue(EmailService.verifyCode(email, "  $code\n", EmailService.PURPOSE_REGISTER))
        assertFalse(EmailService.verifyCode(email, code, EmailService.PURPOSE_REGISTER), "code is single-use")
    }

    @Test
    fun `verifyCode still rejects wrong code after trim`() {
        val email = "trim-bad-${System.nanoTime()}@example.com"
        EmailService.sendVerificationCode(email, EmailService.PURPOSE_RESET)
        assertFalse(EmailService.verifyCode(email, "  000000  ", EmailService.PURPOSE_RESET))
    }
}
