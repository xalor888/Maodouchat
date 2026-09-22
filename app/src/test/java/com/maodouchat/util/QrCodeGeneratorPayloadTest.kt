package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G213b：`QrCodeGenerator` 的载荷编解码，重点是**安全码二维码**。
 *
 * 为什么值得测：safety QR 是「当面扫码核对身份」的安全功能，载荷形如
 * `maodouchat:safety:<b64>:<deviceId>:<b64>:<deviceId>:<b64>`——**用 `:` 连起来**。
 * 而 userId / safetyCode / fingerprint 都是不可信字符串，**里面完全可能含 `:`**。
 * 若不转义，载荷就会歧义：接收方可能把 `a:b` 解析成两段，
 * 「核对指纹」就变成了拿一个被篡改的指纹通过校验。
 *
 * 这里的转义机制是 `encodePart` 用 **Base64 URL 安全字母表**（`A-Za-z0-9-_`，无 padding）——
 * 该字母表里根本没有 `:`，所以任何输入编码后都不会制造分隔符。
 */
class QrCodeGeneratorPayloadTest {

    @Test
    fun simplePayloadsUseTheirDocumentedPrefixes() {
        assertEquals("maodouchat:user:u1", QrCodeGenerator.encodeUserQrPayload("u1"))
        assertEquals("maodouchat:chat:c1", QrCodeGenerator.encodeChatQrPayload("c1"))
    }

    @Test
    fun chatInviteTokenIsTrimmed() {
        // 手工敲/复制的 token 常带首尾空白，不带进去
        assertEquals("maodouchat:chat-invite:v1:tok", QrCodeGenerator.encodeChatInviteQrPayload("  tok  "))
        // ⚠️ 注意：编码侧只 trim，**不校验长度**；解析侧要求 ≥32 字符
        //（AppLinkRouter.MIN_CHAT_INVITE_TOKEN_LENGTH）。所以往返只用长 token 测。
        assertEquals("maodouchat:chat-invite:v1:t", QrCodeGenerator.encodeChatInviteQrPayload("t"))
    }

    @Test
    fun encodedPartsNeverContainTheDelimiter() {
        // 这是整套转义的地基：编码结果里不得出现 ':'（也不得出现 '=' padding）
        listOf("u1", "a:b", ":::", "lead:trail:", "", " ", "中文:冒号", "a\nb", "*".repeat(200))
            .forEach { raw ->
                val payload = QrCodeGenerator.encodeSafetyQrPayload(
                    ownerUserId = raw, ownerDeviceId = 1,
                    peerUserId = raw, peerDeviceId = 2,
                    safetyCode = raw,
                )
                // 除了 5 个结构冒号（前缀1 + 段间4）之外不该有多余的
                // "maodouchat:safety:" 占 2 个，5 段之间 4 个 → 共 6 个结构冒号
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
        assertIs<QrCodeGenerator.QrTarget.Safety>(target)
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
        assertIs<QrCodeGenerator.QrTarget.Safety>(target)
        assertEquals("u1", target.ownerUserId)
        assertEquals(7, target.ownerDeviceId, "deviceId 不得被 safetyCode 里的冒号挤位")
        assertEquals("u2", target.peerUserId, "peerUserId 不得被挤位")
        assertEquals(8, target.peerDeviceId, "peerDeviceId 不得被挤位")
        assertEquals("11:22:33", target.safetyCode, "带冒号的 safetyCode 必须原样往返")
    }

    /**
     * **一个真实的不对称，本轮如实记下（不假装它是bug也不修）**：
     * 编码侧 `encodePart` 会把 userId 里的 `:` 转义掉（Base64），
     * 但解析侧随后又用 `ID_REGEX = ^[a-zA-Z0-9_-]{1,64}$` 拒绝含 `:` 的 userId。
     * 于是「userId 含冒号」的安全码**编得出来、解不动**（parsePayload 返回 null）。
     *
     * 实践上无害：userId 由本机生成、必在该字符集内，走不到这条路。
     * 但这说明 `encodePart` 的转义对 userId 字段其实是**冗余**的，
     * 真正靠它保护的是 safetyCode / fingerprint（那两个字段原样返回、无 ID_REGEX）。
     * 这条测试把「解码侧会拒绝」钉住，避免后人误以为它能往返。
     */
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
        assertIs<QrCodeGenerator.QrTarget.Safety>(target)
        assertEquals("fp1", target.ownerIdentityFingerprint)
        assertEquals("fp2", target.peerIdentityFingerprint)
        // v2 不带 safetyCode
        assertEquals(null, target.safetyCode)
    }

    @Test
    fun parsePayloadRecognisesEveryKind() {
        val user = assertNotNull(QrCodeGenerator.parsePayload(QrCodeGenerator.encodeUserQrPayload("u9")))
        assertIs<QrCodeGenerator.QrTarget.User>(user)
        assertEquals("u9", user.userId)

        val chat = assertNotNull(QrCodeGenerator.parsePayload(QrCodeGenerator.encodeChatQrPayload("c9")))
        assertIs<QrCodeGenerator.QrTarget.Chat>(chat)
        assertEquals("c9", chat.chatId)

        val invite = assertNotNull(
            QrCodeGenerator.parsePayload(
                QrCodeGenerator.encodeChatInviteQrPayload("t".repeat(40)),
            ),
            "40 字符 token 应能往返（解析侧要求 ≥32）",
        )
        assertIs<QrCodeGenerator.QrTarget.ChatInvite>(invite)
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
                assertTrue(target == null || target is QrCodeGenerator.QrTarget,
                    "'$bad' 解析结果异常：$target")
            }
    }
}
