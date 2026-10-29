package com.maodouchat.util

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * safety QR 是「当面扫码核对身份」：载荷用 `:` 分段，但 userId/safetyCode/指纹都可能含 `:`。
 * 转义靠 `encodePart` 的 Base64 URL 安全字母表——它里面根本没有 `:`，所以任何输入都造不出分隔符。
 */
class QrCodeGeneratorPayloadTest {

    @Test
    fun simplePayloadsUseTheirDocumentedPrefixes() {
        assertEquals("maodouchat:user:u1", QrCodeGenerator.encodeUserQrPayload("u1"))
        assertEquals("maodouchat:chat:c1", QrCodeGenerator.encodeChatQrPayload("c1"))
    }

    @Test
    fun chatInviteTokenIsTrimmed() {
        // 手工敲/复制的 token 常带首尾空白，编码侧只 trim、不校验长度（解析侧才要求 ≥32 位）
        assertEquals("maodouchat:chat-invite:v1:tok", QrCodeGenerator.encodeChatInviteQrPayload("  tok  "))
        assertEquals("maodouchat:chat-invite:v1:t", QrCodeGenerator.encodeChatInviteQrPayload("t"))
    }

    @Test
    fun encodedPartsNeverContainTheDelimiter() {
        // 地基断言：编码结果里不得出现 ':'（也不得出现 '=' padding）
        listOf("u1", "a:b", ":::", "lead:trail:", "", " ", "中文:冒号", "a\nb", "*".repeat(200))
            .forEach { raw ->
                val payload = QrCodeGenerator.encodeSafetyQrPayload(
                    ownerUserId = raw, ownerDeviceId = 1,
                    peerUserId = raw, peerDeviceId = 2,
                    safetyCode = raw,
                )
                // "maodouchat:safety:" 占 2 个、5 段之间 4 个 → 共 6 个结构冒号
                val colons = payload.count { it == ':' }
                assertEquals(6, colons, "载荷 [$raw] 的冒号数应为 6（结构位），实际 $colons：$payload")
                assertFalse(payload.contains("="), "Base64 无 padding，不该有 '='：$payload")
            }
    }

    @Test
    fun safetyPayloadRoundTripsForOrdinaryValues() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            safetyCode = "123456",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("u1", target.ownerUserId)
        assertEquals(1, target.ownerDeviceId)
        assertEquals("u2", target.peerUserId)
        assertEquals(2, target.peerDeviceId)
        assertEquals("123456", target.safetyCode)
    }

    @Test
    fun safetyCodeWithColonsRoundTripsAndNeverShiftsTheFields() {
        // 真正需要转义的是 **safetyCode / fingerprint**：它们原样返回、不受 ID_REGEX 约束。
        // 若 Base64 转义失效，":" 会把段数挤多，deviceId 就会错位。
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 7,
            peerUserId = "u2", peerDeviceId = 8,
            safetyCode = "11:22:33",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("u1", target.ownerUserId)
        assertEquals(7, target.ownerDeviceId, "deviceId 不得被 safetyCode 里的冒号挤位")
        assertEquals("u2", target.peerUserId, "peerUserId 不得被挤位")
        assertEquals(8, target.peerDeviceId, "peerDeviceId 不得被挤位")
        assertEquals("11:22:33", target.safetyCode, "带冒号的 safetyCode 必须原样往返")
    }

    // 已知不对称：编码侧 `encodePart` 把 userId 里的 `:` 转义了，但解析侧 ID_REGEX 仍会拒绝含冒号的 userId
    //（实践上无害：userId 本机生成必在该字符集内；真正靠转义保护的是 safetyCode/fingerprint）。
    // 这条测试把「解码侧会拒绝」钉住，避免后人误以为它能往返。
    @Test
    fun aUserIdWithColonsIsRejectedByTheParserNotSilentlyMisparsed() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "a:b:c", ownerDeviceId = 7,
            peerUserId = "u2", peerDeviceId = 8,
            safetyCode = "123456",
        )
        // 结构上是合法的（6 个冒号、Base64 段），没被"截断"
        assertEquals(6, payload.count { it == ':' })
        // 但解析侧按 ID_REGEX 拒绝 → null（而不是给出一个被篡改的 ownerUserId）
        assertNull(QrCodeGenerator.parsePayload(payload),
            "含 ':' 的 userId 应被解析侧拒绝为 null，而不是解析出被篡改的值")
    }

    private fun assertNull(value: Any?, message: String) =
        org.junit.Assert.assertNull(message, value)

    @Test
    fun safetyV2PayloadCarriesBothFingerprints() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            ownerIdentityFingerprint = "fp1", peerIdentityFingerprint = "fp2",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("fp1", target.ownerIdentityFingerprint)
        assertEquals("fp2", target.peerIdentityFingerprint)
        // v2 不带 safetyCode
        assertEquals(null, target.safetyCode)
    }

    @Test
    fun parsePayloadRecognisesEveryKind() {
        val user = assertNotNull(QrCodeGenerator.parsePayload(QrCodeGenerator.encodeUserQrPayload("u9")))
        assertIs<QrTarget.User>(user)
        assertEquals("u9", user.userId)

        val chat = assertNotNull(QrCodeGenerator.parsePayload(QrCodeGenerator.encodeChatQrPayload("c9")))
        assertIs<QrTarget.Chat>(chat)
        assertEquals("c9", chat.chatId)

        val invite = assertNotNull(
            QrCodeGenerator.parsePayload(
                QrCodeGenerator.encodeChatInviteQrPayload("t".repeat(40)),
            ),
            "40 字符 token 应能往返（解析侧要求 ≥32）",
        )
        assertIs<QrTarget.ChatInvite>(invite)
        assertEquals("t".repeat(40), invite.token)
    }

    @Test
    fun garbagePayloadsParseToNullRatherThanThrowing() {
        listOf("", "   ", "garbage", "maodouchat:", "maodouchat:user:", "maodouchat:safety:", "::::", "maodouchat:unknown:x")
            .forEach { bad ->
                val target = runCatching { QrCodeGenerator.parsePayload(bad) }.getOrElse {
                    throw AssertionError("parsePayload('$bad') 抛异常了：${it.message}", it)
                }
                // 允许 null（Invalid）或某种宽松解析，但绝不允许崩
                assertTrue(target == null || target is QrTarget,
                    "'$bad' 解析结果异常：$target")
            }
    }

    @Test
    fun `safety v2 fingerprints with colons round trip`() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            ownerIdentityFingerprint = "fp:1:2", peerIdentityFingerprint = "fp::x",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("fp:1:2", target.ownerIdentityFingerprint)
        assertEquals("fp::x", target.peerIdentityFingerprint)
    }

    @Test
    fun `safety payload device id out of range is rejected`() {
        val valid = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            safetyCode = "123456",
        )
        assertNull(QrCodeGenerator.parsePayload(valid.replaceFirst(":1:", ":0:")), "deviceId 0 不合法")
        assertNull(QrCodeGenerator.parsePayload(valid.replaceFirst(":2:", ":256:")), "deviceId 256 不合法")
    }

    @Test
    fun `overlong payload is rejected`() {
        assertNull(
            QrCodeGenerator.parsePayload("maodouchat:user:" + "x".repeat(2100)),
            "超 2048 字节的载荷不得解析",
        )
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertNull(QrCodeGenerator.parsePayload("javascript:alert(1)"), "危险 scheme 不得解析")
        assertNull(QrCodeGenerator.parsePayload("https://example.com"), "非 maodouchat scheme 不得解析")
    }

    @Test
    fun `chat invite token length boundary`() {
        assertNull(
            QrCodeGenerator.parsePayload(QrCodeGenerator.encodeChatInviteQrPayload("t".repeat(31))),
            "31 字符 token 不足 32 位下限",
        )
        val ok = QrCodeGenerator.parsePayload(QrCodeGenerator.encodeChatInviteQrPayload("t".repeat(32)))
        assertIs<QrTarget.ChatInvite>(ok)
        assertEquals("t".repeat(32), ok.token)
    }

    @Test
    fun emptyIdsEncodeToBarePrefixes() {
        assertEquals("maodouchat:user:", QrCodeGenerator.encodeUserQrPayload(""))
        assertEquals("maodouchat:chat:", QrCodeGenerator.encodeChatQrPayload(""))
    }

    @Test
    fun unicodeIdsKeepSixColonStructure() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "用户甲", ownerDeviceId = 1,
            peerUserId = "用户乙", peerDeviceId = 2,
            safetyCode = "安全码",
        )
        assertEquals(6, payload.count { it == ':' })
        assertFalse(payload.contains("="))
    }

    @Test
    fun safetyV2PayloadHasEightColonsAndV2Marker() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            ownerIdentityFingerprint = "fp1", peerIdentityFingerprint = "fp2",
        )
        assertEquals(8, payload.count { it == ':' })
        assertTrue(payload.startsWith("maodouchat:safety:v2:"))
        assertFalse(payload.contains("="))
    }

    @Test
    fun whitespaceOnlyInviteTokenTrimsToBarePrefixAndParsesNull() {
        val payload = QrCodeGenerator.encodeChatInviteQrPayload("   ")
        assertEquals("maodouchat:chat-invite:v1:", payload)
        assertNull(QrCodeGenerator.parsePayload(payload), "全空白 token 应解析为 null")
    }

    @Test
    fun inviteTokenInnerSpacesArePreserved() {
        assertEquals(
            "maodouchat:chat-invite:v1:a b",
            QrCodeGenerator.encodeChatInviteQrPayload("  a b  ")
        )
    }

    @Test
    fun safetyV1PayloadHasNoV2Marker() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 2,
            safetyCode = "123456",
        )
        assertTrue(payload.startsWith("maodouchat:safety:"))
        assertFalse(payload.startsWith("maodouchat:safety:v2:"))
        assertEquals(6, payload.count { it == ':' })
        assertFalse(payload.contains("="))
    }

    @Test
    fun deviceIdBoundariesOneAnd255RoundTrip() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 1,
            peerUserId = "u2", peerDeviceId = 255,
            safetyCode = "123456",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals(1, target.ownerDeviceId)
        assertEquals(255, target.peerDeviceId)
    }

    @Test
    fun inviteTokenWithSurroundingSpacesTrimsToExactly32AndParses() {
        val token = "t".repeat(32)
        val payload = QrCodeGenerator.encodeChatInviteQrPayload("  $token  ")
        assertEquals("maodouchat:chat-invite:v1:$token", payload)
        val parsed = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.ChatInvite>(parsed)
        assertEquals(token, parsed.token)
    }

    @Test
    fun safetyCodeWithNewlineAndSpacesRoundTrips() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 3,
            peerUserId = "u2", peerDeviceId = 4,
            safetyCode = "a b\nc",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("a b\nc", target.safetyCode)
        assertEquals(3, target.ownerDeviceId)
        assertEquals(4, target.peerDeviceId)
    }

    @Test
    fun safetyV2FingerprintsWithSpacesRoundTripAndCodeStaysNull() {
        val payload = QrCodeGenerator.encodeSafetyQrPayload(
            ownerUserId = "u1", ownerDeviceId = 255,
            peerUserId = "u2", peerDeviceId = 1,
            ownerIdentityFingerprint = "fp 1", peerIdentityFingerprint = "fp 2",
        )
        val target = QrCodeGenerator.parsePayload(payload)
        assertIs<QrTarget.Safety>(target)
        assertEquals("fp 1", target.ownerIdentityFingerprint)
        assertEquals("fp 2", target.peerIdentityFingerprint)
        assertEquals(null, target.safetyCode)
        assertEquals(255, target.ownerDeviceId)
        assertEquals(1, target.peerDeviceId)
    }
}
