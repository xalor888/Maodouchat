package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.security.MessageSafetyScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G162：`ChatDetailScreenHelpers.safetyDetailText` 的测试（G162 刚从 messageSafetyWarning 抽出）。
 *
 * 原函数是 `@Composable`，只能靠仪器测试覆盖；抽出后普通 JVM 单测就能逐分支断言。
 * 返回 `(资源 id, host)` 二元组——host 只有「可疑链接」那一支可能非空。
 */
class MessageSafetyDetailResTest {

    private val S = MessageSafetyScanner

    @Test
    fun `each single code maps to its own string with no host`() {
        val cases = listOf(
            S.CODE_PAYMENT_INDUCEMENT to R.string.chat_safety_payment,
            S.CODE_IMPERSONATION to R.string.chat_safety_impersonation,
            S.CODE_CREDENTIAL_REQUEST to R.string.chat_safety_credential,
            S.CODE_SENSITIVE_DATA to R.string.chat_safety_sensitive_data,
        )
        cases.forEach { (code, res) ->
            val (gotRes, gotHost) = safetyDetailText(code, null)
            assertEquals("$code 的资源不对", res, gotRes)
            assertNull("$code 不该带 host", gotHost)
        }
    }

    @Test
    fun `suspicious link with a host uses the host variant`() {
        val (res, host) = safetyDetailText(S.CODE_SUSPICIOUS_LINK, "evil.example.com")
        assertEquals(R.string.chat_safety_suspicious_link_host, res)
        assertEquals("evil.example.com", host)
    }

    @Test
    fun `suspicious link without a usable host uses the plain variant`() {
        // null / 空串 / 全空白都算「没有主机」——否则文案里会出现一个空括号
        listOf(null, "", "   ", "\t", "\n").forEach { matched ->
            val (res, host) = safetyDetailText(S.CODE_SUSPICIOUS_LINK, matched)
            assertEquals("matched=$matched 应走无主机版", R.string.chat_safety_suspicious_link, res)
            assertNull("matched=$matched 不该带 host", host)
        }
    }

    @Test
    fun `the two suspicious link variants are different strings`() {
        assertNotEquals(
            safetyDetailText(S.CODE_SUSPICIOUS_LINK, "host.com").first,
            safetyDetailText(S.CODE_SUSPICIOUS_LINK, null).first,
        )
    }

    @Test
    fun `unknown code falls back to the generic string`() {
        assertEquals(R.string.chat_safety_generic, safetyDetailText("no_such_code", null).first)
        assertEquals(R.string.chat_safety_generic, safetyDetailText("", null).first)
        // 大小写敏感：全大写走不到已知分支
        assertEquals(R.string.chat_safety_generic, safetyDetailText("SUSPICIOUS_LINK", "h").first)
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
            assertNull("$code 不该带 host", safetyDetailText(code, "some.host").second)
        }
    }

    @Test
    fun `every code resolves to a non zero resource`() {
        listOf(
            S.CODE_SUSPICIOUS_LINK, S.CODE_PAYMENT_INDUCEMENT, S.CODE_IMPERSONATION,
            S.CODE_CREDENTIAL_REQUEST, S.CODE_SENSITIVE_DATA, "whatever",
        ).forEach { code ->
            assertTrue("$code 解析出 0", safetyDetailText(code, "h").first != 0)
        }
    }
}
