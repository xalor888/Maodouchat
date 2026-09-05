package com.maodouchat.contacts

import com.maodouchat.util.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun parse_validUser() {
        val payload = "maodouchat:user:user_12345-abc"
        val parsed = QrPayloadParser.parse(payload)
        assertTrue(parsed is QrParsedPayload.User)
        assertEquals("user_12345-abc", (parsed as QrParsedPayload.User).userId)

        // 验证 QrCodeGenerator.parsePayload 向后兼容性
        val target = QrCodeGenerator.parsePayload(payload)
        assertTrue(target is QrCodeGenerator.QrTarget.User)
        assertEquals("user_12345-abc", (target as QrCodeGenerator.QrTarget.User).userId)
    }

    @Test
    fun parse_validChat() {
        val payload = "maodouchat:chat:chat-999-grp"
        val parsed = QrPayloadParser.parse(payload)
        assertTrue(parsed is QrParsedPayload.Chat)
        assertEquals("chat-999-grp", (parsed as QrParsedPayload.Chat).chatId)
    }

    @Test
    fun parse_validChatInvite() {
        val token = "a".repeat(40)
        val payload = "maodouchat:chat-invite:v1:$token"
        val parsed = QrPayloadParser.parse(payload)
        assertTrue(parsed is QrParsedPayload.ChatInvite)
        assertEquals(token, (parsed as QrParsedPayload.ChatInvite).token)
    }

    @Test
    fun parse_validSafetyV1() {
        val encoded = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "alice",
            ownerDeviceId = 1,
            peerUserId = "bob",
            peerDeviceId = 2,
            safetyCode = "123456"
        )
        val parsed = QrPayloadParser.parse(encoded)
        assertTrue(parsed is QrParsedPayload.Safety)
        val safety = parsed as QrParsedPayload.Safety
        assertEquals(1, safety.version)
        assertEquals("alice", safety.ownerUserId)
        assertEquals(1, safety.ownerDeviceId)
        assertEquals("bob", safety.peerUserId)
        assertEquals(2, safety.peerDeviceId)
        assertEquals("123456", safety.safetyCode)
    }

    @Test
    fun parse_validSafetyV2() {
        val encoded = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "alice",
            ownerDeviceId = 1,
            peerUserId = "bob",
            peerDeviceId = 2,
            ownerIdentityFingerprint = "fp-alice-12345",
            peerIdentityFingerprint = "fp-bob-67890"
        )
        val parsed = QrPayloadParser.parse(encoded)
        assertTrue(parsed is QrParsedPayload.Safety)
        val safety = parsed as QrParsedPayload.Safety
        assertEquals(2, safety.version)
        assertEquals("alice", safety.ownerUserId)
        assertEquals(1, safety.ownerDeviceId)
        assertEquals("bob", safety.peerUserId)
        assertEquals(2, safety.peerDeviceId)
        assertEquals("fp-alice-12345", safety.ownerIdentityFingerprint)
        assertEquals("fp-bob-67890", safety.peerIdentityFingerprint)
    }

    @Test
    fun parse_blocksEmptyOrNull() {
        assertTrue(QrPayloadParser.parse(null) is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("") is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("   ") is QrParsedPayload.Invalid)
        assertNull(QrCodeGenerator.parsePayload(""))
    }

    @Test
    fun parse_blocksOversizedPayloadDos() {
        val hugePayload = "maodouchat:user:" + "a".repeat(2500)
        val parsed = QrPayloadParser.parse(hugePayload)
        assertTrue(parsed is QrParsedPayload.Invalid)
        assertTrue((parsed as QrParsedPayload.Invalid).reason.contains("exceeds maximum"))
        assertNull(QrCodeGenerator.parsePayload(hugePayload))
    }

    @Test
    fun parse_blocksDangerousSchemes() {
        val maliciousSchemes = listOf(
            "javascript:alert('xss')",
            "file:///data/data/com.maodouchat/databases/app.db",
            "content://com.android.providers.media/external/images",
            "http://evil.com/phish",
            "https://attacker.site/exploit"
        )
        for (url in maliciousSchemes) {
            val parsed = QrPayloadParser.parse(url)
            assertTrue("Expected invalid for $url", parsed is QrParsedPayload.Invalid)
            assertNull(QrCodeGenerator.parsePayload(url))
        }
    }

    @Test
    fun parse_blocksPathTraversalAndInjectionsInUserId() {
        val dangerousUserIds = listOf(
            "maodouchat:user:../../etc/passwd",
            "maodouchat:user:<script>alert(1)</script>",
            "maodouchat:user:user' OR '1'='1",
            "maodouchat:user:user\u0000admin",
            "maodouchat:user:user with space"
        )
        for (payload in dangerousUserIds) {
            val parsed = QrPayloadParser.parse(payload)
            assertTrue("Expected invalid for $payload", parsed is QrParsedPayload.Invalid)
            assertNull(QrCodeGenerator.parsePayload(payload))
        }
    }

    @Test
    fun parse_blocksInvalidInviteTokenFormat() {
        // 太短 (< 32)
        val tooShort = "maodouchat:chat-invite:v1:abc123"
        assertTrue(QrPayloadParser.parse(tooShort) is QrParsedPayload.Invalid)

        // 包含非法字符
        val illegalChars = "maodouchat:chat-invite:v1:" + "a".repeat(30) + "!@#"
        assertTrue(QrPayloadParser.parse(illegalChars) is QrParsedPayload.Invalid)
    }

    @Test
    fun parse_blocksDangerousDeviceIdInSafetyPayload() {
        // deviceId 为 0 (非法，必须 1..255)
        val invalidZero = "maodouchat:safety:YWxpY2U=:0:Ym9i:2:MTIz"
        assertTrue(QrPayloadParser.parse(invalidZero) is QrParsedPayload.Invalid)

        // deviceId 超过 255
        val overflow = "maodouchat:safety:YWxpY2U=:256:Ym9i:2:MTIz"
        assertTrue(QrPayloadParser.parse(overflow) is QrParsedPayload.Invalid)

        // deviceId 为负数
        val negative = "maodouchat:safety:YWxpY2U=:-1:Ym9i:2:MTIz"
        assertTrue(QrPayloadParser.parse(negative) is QrParsedPayload.Invalid)

        // deviceId 为非数字
        val nonNumeric = "maodouchat:safety:YWxpY2U=:nan:Ym9i:2:MTIz"
        assertTrue(QrPayloadParser.parse(nonNumeric) is QrParsedPayload.Invalid)
    }
}
