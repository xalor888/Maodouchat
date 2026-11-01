package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSafetyScannerTest {

    @Test
    fun `blank text yields no findings`() {
        assertTrue(MessageSafetyScanner.scan("").isEmpty())
        assertTrue(MessageSafetyScanner.scan("   ").isEmpty())
    }

    @Test
    fun `suspicious link is flagged`() {
        val findings = MessageSafetyScanner.scan("请打开 http://secure-login.tk/verify 处理")
        assertTrue(findings.any { it.code == MessageSafetyScanner.CODE_SUSPICIOUS_LINK })
    }

    @Test
    fun `payment inducement is flagged`() {
        val findings = MessageSafetyScanner.scan("请立刻转账到我的支付宝，今天必须完成")
        assertTrue(findings.any { it.code == MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT })
    }

    @Test
    fun `trusted host yields no findings`() {
        assertTrue(MessageSafetyScanner.scan("看看这个 https://github.com/xalor888").isEmpty())
    }
}
