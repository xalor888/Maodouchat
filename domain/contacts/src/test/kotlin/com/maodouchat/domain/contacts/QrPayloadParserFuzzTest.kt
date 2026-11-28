package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrPayloadParserFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        private const val GARBAGE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_:/?# _-.%+=&<>\"'\n\t@：中文😀"
    }

    private fun randomId(random: Random, maxLength: Int = 64): String {
        val length = random.nextInt(1, maxLength + 1)
        return buildString { repeat(length) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }
    }

    private fun randomToken(random: Random): String {
        val length = random.nextInt(32, 81)
        return buildString { repeat(length) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }
    }

    private fun randomGarbage(random: Random, maxLength: Int = 120): String {
        val length = random.nextInt(0, maxLength + 1)
        return buildString { repeat(length) { append(GARBAGE_ALPHABET[random.nextInt(GARBAGE_ALPHABET.length)]) } }
    }

    @Test
    fun `random garbage never throws and valid results require the scheme`() {
        val random = Random(0x51_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val raw = randomGarbage(random)
            val result = QrPayloadParser.parse(raw)
            if (result !is QrParsedPayload.Invalid) {
                assertTrue(
                    raw.trim().startsWith("maodouchat:"),
                    "fuzz 迭代 #$i：有效结果 $result 必须来自 maodouchat: 载荷，raw=$raw"
                )
            }
        }
    }

    @Test
    fun `valid user and chat payloads round trip through the codec`() {
        val random = Random(0x52_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val userId = randomId(random)
            assertEquals(
                QrParsedPayload.User(userId),
                QrPayloadParser.parse(QrPayloadCodec.encodeUserQrPayload(userId)),
                "fuzz 迭代 #$i：user 往返必须恒等"
            )
            val chatId = randomId(random)
            assertEquals(
                QrParsedPayload.Chat(chatId),
                QrPayloadParser.parse(QrPayloadCodec.encodeChatQrPayload(chatId)),
                "fuzz 迭代 #$i：chat 往返必须恒等"
            )
        }
    }

    @Test
    fun `chat invite tokens round trip and bad tokens are invalid`() {
        val random = Random(0x53_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val token = randomToken(random)
            assertEquals(
                QrParsedPayload.ChatInvite(token),
                QrPayloadParser.parse(QrPayloadCodec.encodeChatInviteQrPayload(token)),
                "fuzz 迭代 #$i：invite 往返必须恒等"
            )
            val pos = random.nextInt(token.length)
            val slashed = token.replaceRange(pos, pos + 1, "/")
            assertTrue(
                QrPayloadParser.parse(QrPayloadCodec.encodeChatInviteQrPayload(slashed)) is QrParsedPayload.Invalid,
                "fuzz 迭代 #$i：含 / 的 token 必须 Invalid"
            )
        }
        assertTrue(QrPayloadParser.parse("maodouchat:chat-invite:v1:" + "a".repeat(31)) is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("maodouchat:chat-invite:v1:" + "a".repeat(81)) is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("maodouchat:chat-invite:v1:" + "a".repeat(31) + "?x") is QrParsedPayload.Invalid)
    }

    @Test
    fun `safety v1 and v2 payloads round trip through the codec`() {
        val random = Random(0x54_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val owner = randomId(random)
            val peer = randomId(random)
            val ownerDevice = random.nextInt(1, 256)
            val peerDevice = random.nextInt(1, 256)
            val code = randomId(random)
            assertEquals(
                QrParsedPayload.Safety(
                    version = 1,
                    ownerUserId = owner,
                    ownerDeviceId = ownerDevice,
                    peerUserId = peer,
                    peerDeviceId = peerDevice,
                    safetyCode = code
                ),
                QrPayloadParser.parse(
                    QrPayloadCodec.encodeSafetyQrPayload(owner, ownerDevice, peer, peerDevice, code)
                ),
                "fuzz 迭代 #$i：safety v1 往返必须恒等"
            )
            val ownerFp = randomId(random)
            val peerFp = randomId(random)
            assertEquals(
                QrParsedPayload.Safety(
                    version = 2,
                    ownerUserId = owner,
                    ownerDeviceId = ownerDevice,
                    peerUserId = peer,
                    peerDeviceId = peerDevice,
                    ownerIdentityFingerprint = ownerFp,
                    peerIdentityFingerprint = peerFp
                ),
                QrPayloadParser.parse(
                    QrPayloadCodec.encodeSafetyQrPayload(owner, ownerDevice, peer, peerDevice, ownerFp, peerFp)
                ),
                "fuzz 迭代 #$i：safety v2 往返必须恒等"
            )
        }
    }

    @Test
    fun `overlong payloads are invalid`() {
        val longUser = "maodouchat:user:" + "a".repeat(2040)
        val longResult = QrPayloadParser.parse(longUser)
        assertTrue(longResult is QrParsedPayload.Invalid)
        assertTrue((longResult as QrParsedPayload.Invalid).reason.contains("exceeds maximum"))
        val garbage = "x".repeat(2049)
        val garbageResult = QrPayloadParser.parse(garbage)
        assertTrue(garbageResult is QrParsedPayload.Invalid)
        assertTrue((garbageResult as QrParsedPayload.Invalid).reason.contains("exceeds maximum"))
    }

    @Test
    fun `dangerous and unknown schemes are invalid`() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "http://example.com/x",
            "https://example.com/x",
            "content://com.example/x",
            "intent://x#Intent;scheme=https;end",
            "maodouchatx:user:abc",
            "maodouchat:unknown:abc",
            "maodouchat:",
            "maodouchat:user:",
            "maodouchat:chat:"
        ).forEach { raw ->
            assertTrue(QrPayloadParser.parse(raw) is QrParsedPayload.Invalid, "raw=$raw 必须 Invalid")
        }
    }

    @Test
    fun `uppercase scheme passes the gate but fails dispatch`() {
        // scheme 门禁忽略大小写，但 kind 分发区分大小写：现行行为是大写 scheme 一律 Invalid。
        listOf(
            "MAODOUCHAT:user:abc",
            "Maodouchat:user:abc",
            "maodouchat:USER:abc",
            "maodouchat:User:abc"
        ).forEach { raw ->
            assertTrue(QrPayloadParser.parse(raw) is QrParsedPayload.Invalid, "raw=$raw 必须 Invalid")
        }
        assertEquals(QrParsedPayload.User("abc"), QrPayloadParser.parse("maodouchat:user:abc"))
    }

    @Test
    fun `null and blank inputs are invalid`() {
        assertTrue(QrPayloadParser.parse(null) is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("") is QrParsedPayload.Invalid)
        assertTrue(QrPayloadParser.parse("   ") is QrParsedPayload.Invalid)
    }
}
