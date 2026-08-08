package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSafetyScannerTest {
    @Test
    fun blankReturnsEmpty() {
        assertTrue(MessageSafetyScanner.scan("").isEmpty())
        assertTrue(MessageSafetyScanner.scan("   ").isEmpty())
    }

    @Test
    fun flagsSuspiciousLinkWithoutApi34Results() {
        val findings = MessageSafetyScanner.scan("请打开 http://secure-login.tk/verify 处理")
        assertTrue(findings.any { it.code == MessageSafetyScanner.CODE_SUSPICIOUS_LINK })
    }

    @Test
    fun flagsPaymentInducementWithUrgency() {
        val findings = MessageSafetyScanner.scan("请立刻转账到我的支付宝，今天必须完成")
        assertTrue(findings.any { it.code == MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT })
    }

    @Test
    fun respectsMaxFindingsCap() {
        val findings = MessageSafetyScanner.scan(
            "官方客服紧急：立刻转账并发送验证码，卡号 6222021234567890123 http://claim-prize.gq/x",
            maxFindings = 2
        )
        assertEquals(2, findings.size)
    }
}
