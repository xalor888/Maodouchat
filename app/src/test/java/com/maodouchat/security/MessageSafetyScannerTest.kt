package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun flagsImpersonationWhenUrgencyPresent() {
        val findings = MessageSafetyScanner.scan("官方客服提醒：你的账户将被冻结，请立即处理")
        assertEquals(1, findings.size)
        assertEquals(MessageSafetyScanner.CODE_IMPERSONATION, findings[0].code)
        // 只有冒充词没有紧迫/支付词时不标记
        assertTrue(MessageSafetyScanner.scan("官方客服：请问有什么可以帮您").isEmpty())
    }

    @Test
    fun flagsCredentialHarvestRequests() {
        val findings = MessageSafetyScanner.scan("请把短信验证码发给我")
        assertEquals(1, findings.size)
        assertEquals(MessageSafetyScanner.CODE_CREDENTIAL_REQUEST, findings[0].code)
        // 只有验证码词没有索要动词时不标记
        assertTrue(MessageSafetyScanner.scan("验证码一般是 6 位数字").isEmpty())
    }

    @Test
    fun flagsBankCardAndIdCardAsSensitiveData() {
        val card = MessageSafetyScanner.scan("我的银行卡号是 6222021234567890123，请不要告诉别人")
        assertEquals(1, card.size)
        assertEquals(MessageSafetyScanner.CODE_SENSITIVE_DATA, card[0].code)
        // 卡号里的支付词没有转账/紧迫/URL 配合时，不误报 payment
        assertFalse(card.any { it.code == MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT })
        val idCard = MessageSafetyScanner.scan("身份证号 110105199001011234")
        assertEquals(1, idCard.size)
        assertEquals(MessageSafetyScanner.CODE_SENSITIVE_DATA, idCard[0].code)
    }

    @Test
    fun paymentWithUrlTriggersInducementWithoutUrgency() {
        // 第二分支：支付词 + URL 即使没有紧迫词也标记
        val findings = MessageSafetyScanner.scan("用支付宝在 https://pay.example.com/order 完成支付")
        assertTrue(findings.any { it.code == MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT })
        // 可信域名的支付链接不连带报可疑链接
        assertFalse(findings.any { it.code == MessageSafetyScanner.CODE_SUSPICIOUS_LINK })
        // 只有支付词、没有转账/紧迫/URL 时不标记
        assertTrue(MessageSafetyScanner.scan("支付宝用起来真方便").isEmpty())
    }

    @Test
    fun trustedHostsAreNotFlaggedButShortLinksAre() {
        assertTrue(MessageSafetyScanner.scan("看看这个 https://github.com/xalor888").isEmpty())
        val short = MessageSafetyScanner.scan("点击 http://bit.ly/abc123 查看详情")
        assertEquals(1, short.size)
        assertEquals(MessageSafetyScanner.CODE_SUSPICIOUS_LINK, short[0].code)
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
