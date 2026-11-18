package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageSafetyLabelsTest {

    private val S = MessageSafetyScanner

    @Test
    fun `each single code maps to its own kind with no host`() {
        val cases = mapOf(
            S.CODE_PAYMENT_INDUCEMENT to MessageSafetyDetailKind.PaymentInducement,
            S.CODE_IMPERSONATION to MessageSafetyDetailKind.Impersonation,
            S.CODE_CREDENTIAL_REQUEST to MessageSafetyDetailKind.CredentialRequest,
            S.CODE_SENSITIVE_DATA to MessageSafetyDetailKind.SensitiveData,
        )
        cases.forEach { (code, expected) ->
            val (kind, host) = messageSafetyDetailKind(code, null)
            assertEquals(expected, kind, code)
            assertNull(host, code)
        }
    }

    @Test
    fun `suspicious link with a host uses the host variant`() {
        val (kind, host) = messageSafetyDetailKind(S.CODE_SUSPICIOUS_LINK, "evil.example.com")
        assertEquals(MessageSafetyDetailKind.SuspiciousLinkHost, kind)
        assertEquals("evil.example.com", host)
    }

    @Test
    fun `suspicious link without a usable host uses the plain variant`() {
        listOf(null, "", "   ", "\t", "\n").forEach { matched ->
            val (kind, host) = messageSafetyDetailKind(S.CODE_SUSPICIOUS_LINK, matched)
            assertEquals(MessageSafetyDetailKind.SuspiciousLink, kind, "matched=$matched")
            assertNull(host, "matched=$matched")
        }
    }

    @Test
    fun `unknown code falls back to generic`() {
        assertEquals(MessageSafetyDetailKind.Generic, messageSafetyDetailKind("no_such_code", null).first)
        assertEquals(MessageSafetyDetailKind.Generic, messageSafetyDetailKind("", null).first)
        // 大小写敏感：全大写走不到已知分支
        assertEquals(MessageSafetyDetailKind.Generic, messageSafetyDetailKind("SUSPICIOUS_LINK", "h").first)
    }

    @Test
    fun `only suspicious link ever carries a host`() {
        listOf(
            S.CODE_PAYMENT_INDUCEMENT,
            S.CODE_IMPERSONATION,
            S.CODE_CREDENTIAL_REQUEST,
            S.CODE_SENSITIVE_DATA,
            "unknown",
        ).forEach { code ->
            assertNull(messageSafetyDetailKind(code, "some.host").second, code)
        }
    }
}
