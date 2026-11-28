package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.EncryptedMessageEnvelope
import com.maodouchat.core.crypto.MultiDeviceEnvelopePolicy
import com.maodouchat.core.crypto.MultiDeviceMessageEntry
import com.maodouchat.core.crypto.MultiDeviceMessageEnvelope
import com.maodouchat.core.crypto.SenderKeyDistributionEnvelope
import com.maodouchat.core.crypto.SenderKeyMessageEnvelope
import com.maodouchat.core.crypto.SignalProtocolConstants
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalEnvelopeJsonFuzzTest {

    private companion object {
        private const val ITERATIONS = 200

        private val GARBAGE_PARTS = listOf(
            "{", "}", "\"", ":", ",", "[", "]", "version", "algorithm", "ciphertext",
            "entries", "payloadType", "groupId", "distributionId", "epoch", "1", "2",
            "0", "3", "99", "-1", "null", "true", "TEXT", "IMAGE", "signal", " ",
            "\n", "x", "é", "Y2lwaGVy",
        )
        private val PAYLOAD_TYPES: List<String?> = listOf(null, "TEXT", "IMAGE", "", "CALL")
        private val CIPHERTEXT_TYPES: List<String?> = listOf(null, "signal", "prekey", "unknown", "bogus")
        private val ALGORITHMS = listOf(
            SignalProtocolConstants.ALGORITHM_SIGNAL,
            SignalProtocolConstants.ALGORITHM_SIGNAL_MULTI_DEVICE,
            SignalProtocolConstants.ALGORITHM_SENDER_KEY,
            SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION,
            "", "bogus",
        )

        private fun randomGarbage(random: Random): String {
            val parts = random.nextInt(0, 11)
            return List(parts) { GARBAGE_PARTS.random(random) }.joinToString("")
        }

        private fun randomCiphertext(random: Random): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
            return List(random.nextInt(0, 13)) { chars.random(random) }.joinToString("")
        }

        private fun randomText(random: Random): String {
            val parts = random.nextInt(0, 4)
            return List(parts) { listOf("g-1", "d-1", "u1", "x", "-", "_", "2").random(random) }.joinToString("")
        }

        private fun randomEncrypted(random: Random): EncryptedMessageEnvelope =
            EncryptedMessageEnvelope(
                version = listOf(-1, 0, 1, 2, 3, 99).random(random),
                algorithm = ALGORITHMS.random(random),
                senderDeviceId = random.nextInt(0, 300),
                recipientDeviceId = if (random.nextBoolean()) null else random.nextInt(0, 300),
                ciphertextType = CIPHERTEXT_TYPES.random(random),
                payloadType = PAYLOAD_TYPES.random(random),
                ciphertext = randomCiphertext(random),
            )

        private fun randomSenderKey(random: Random): SenderKeyMessageEnvelope =
            SenderKeyMessageEnvelope(
                version = listOf(-1, 0, 1, 2, 99).random(random),
                algorithm = ALGORITHMS.random(random),
                groupId = randomText(random),
                epoch = listOf(-5L, 0L, 1L, 5L, 100L).random(random),
                senderDeviceId = random.nextInt(0, 300),
                distributionId = randomText(random),
                payloadType = PAYLOAD_TYPES.random(random) ?: "TEXT",
                ciphertext = randomCiphertext(random),
            )

        private fun randomDistribution(random: Random): SenderKeyDistributionEnvelope =
            SenderKeyDistributionEnvelope(
                version = listOf(-1, 0, 1, 2, 99).random(random),
                algorithm = ALGORITHMS.random(random),
                groupId = randomText(random),
                epoch = listOf(-5L, 0L, 1L, 3L).random(random),
                senderDeviceId = random.nextInt(0, 300),
                distributionId = randomText(random),
                distributionMessage = randomCiphertext(random),
            )

        private fun randomMultiDevice(random: Random): MultiDeviceMessageEnvelope =
            MultiDeviceMessageEnvelope(
                version = listOf(0, 1, 2, 3, 99).random(random),
                algorithm = ALGORITHMS.random(random),
                senderDeviceId = random.nextInt(0, 300),
                payloadType = PAYLOAD_TYPES.random(random),
                entries = List(random.nextInt(0, 4)) {
                    MultiDeviceMessageEntry(
                        recipientUserId = if (random.nextBoolean()) null else "u${random.nextInt(0, 3)}",
                        recipientDeviceId = listOf(-1, 0, 1, 2, 255, 256).random(random),
                        ciphertextType = CIPHERTEXT_TYPES.random(random),
                        ciphertext = if (random.nextInt(0, 5) == 0) "   " else randomCiphertext(random),
                    )
                },
            )

        private fun encode(value: Any): String = when (value) {
            is EncryptedMessageEnvelope ->
                SignalProtocolConstants.json.encodeToString(value)
            is SenderKeyMessageEnvelope ->
                SignalProtocolConstants.json.encodeToString(value)
            is SenderKeyDistributionEnvelope ->
                SignalProtocolConstants.json.encodeToString(value)
            is MultiDeviceMessageEnvelope ->
                SignalProtocolConstants.json.encodeToString(value)
            else -> error("unexpected $value")
        }
    }

    @Test
    fun `random garbage never throws and is deterministic`() {
        val random = Random(0xA2_2060)
        repeat(ITERATIONS) { i ->
            val content = randomGarbage(random)
            val first = runCatching {
                listOf(
                    SignalEnvelopeJson.envelopePayloadType(content),
                    SignalEnvelopeJson.isEncryptedEnvelope(content),
                    SignalEnvelopeJson.isSenderKeyEnvelope(content),
                    SignalEnvelopeJson.isSenderKeyDistributionEnvelope(content),
                    SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(content),
                    SignalEnvelopeJson.parseEncryptedMessageEnvelope(content),
                    SignalEnvelopeJson.parseSenderKeyMessageEnvelope(content),
                    SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(content),
                )
            }
            assertTrue(first.isSuccess, "fuzz 迭代 #$i：随机输入不得抛：<$content>")
            val second = listOf(
                SignalEnvelopeJson.envelopePayloadType(content),
                SignalEnvelopeJson.isEncryptedEnvelope(content),
                SignalEnvelopeJson.isSenderKeyEnvelope(content),
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(content),
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(content),
                SignalEnvelopeJson.parseEncryptedMessageEnvelope(content),
                SignalEnvelopeJson.parseSenderKeyMessageEnvelope(content),
                SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(content),
            )
            assertEquals(first.getOrThrow(), second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
        }
    }

    @Test
    fun `encrypted envelope structured fuzz`() {
        val random = Random(0xA2_2061)
        repeat(ITERATIONS) { i ->
            val envelope = randomEncrypted(random)
            val json = encode(envelope)
            assertEquals(
                envelope, SignalEnvelopeJson.parseEncryptedMessageEnvelope(json),
                "fuzz 迭代 #$i：加密信封必须往返恒等",
            )
            assertEquals(
                envelope.version >= 1, SignalEnvelopeJson.isEncryptedEnvelope(json),
                "fuzz 迭代 #$i：isEncryptedEnvelope 只看 version >= 1：version=${envelope.version}",
            )
            assertEquals(
                envelope.payloadType, SignalEnvelopeJson.envelopePayloadType(json),
                "fuzz 迭代 #$i：payloadType 必须原样返回",
            )
            assertFalse(SignalEnvelopeJson.isSenderKeyEnvelope(json), "fuzz 迭代 #$i：加密信封不是 SenderKey 信封")
            assertFalse(
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json),
                "fuzz 迭代 #$i：加密信封不是分发信封",
            )
            assertNull(
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(json),
                "fuzz 迭代 #$i：加密信封无 SenderKey epoch",
            )
        }
    }

    @Test
    fun `sender key envelope structured fuzz`() {
        val random = Random(0xA2_2062)
        repeat(ITERATIONS) { i ->
            val envelope = randomSenderKey(random)
            val json = encode(envelope)
            assertEquals(
                envelope, SignalEnvelopeJson.parseSenderKeyMessageEnvelope(json),
                "fuzz 迭代 #$i：SenderKey 信封必须往返恒等",
            )
            assertEquals(
                envelope.version == SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION &&
                    envelope.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY,
                SignalEnvelopeJson.isSenderKeyEnvelope(json),
                "fuzz 迭代 #$i：isSenderKeyEnvelope 只认 version=1 + SenderKey 算法",
            )
            assertEquals(
                envelope.epoch.takeIf { it > 0L },
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(json),
                "fuzz 迭代 #$i：epoch 只取正数",
            )
            assertEquals(
                envelope.version >= 1, SignalEnvelopeJson.isEncryptedEnvelope(json),
                "fuzz 迭代 #$i：SenderKey 信封按加密信封解码时只看 version >= 1",
            )
            assertEquals(
                envelope.payloadType, SignalEnvelopeJson.envelopePayloadType(json),
                "fuzz 迭代 #$i：payloadType 必须原样返回",
            )
            assertFalse(
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json),
                "fuzz 迭代 #$i：SenderKey 信封不是分发信封",
            )
        }
    }

    @Test
    fun `sender key distribution envelope structured fuzz`() {
        val random = Random(0xA2_2063)
        repeat(ITERATIONS) { i ->
            val envelope = randomDistribution(random)
            val json = encode(envelope)
            assertEquals(
                envelope, SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(json),
                "fuzz 迭代 #$i：分发信封必须往返恒等",
            )
            assertEquals(
                envelope.version == SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION &&
                    envelope.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION,
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json),
                "fuzz 迭代 #$i：isSenderKeyDistributionEnvelope 只认 version=1 + 分发算法",
            )
            assertFalse(SignalEnvelopeJson.isEncryptedEnvelope(json), "fuzz 迭代 #$i：分发信封不是加密信封")
            assertNull(SignalEnvelopeJson.envelopePayloadType(json), "fuzz 迭代 #$i：分发信封无 payloadType")
            assertFalse(SignalEnvelopeJson.isSenderKeyEnvelope(json), "fuzz 迭代 #$i：分发信封不是 SenderKey 信封")
            assertNull(
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(json),
                "fuzz 迭代 #$i：分发信封无 SenderKey epoch",
            )
        }
    }

    @Test
    fun `multi device envelope structured fuzz agrees with the policy parser`() {
        val random = Random(0xA2_2064)
        repeat(ITERATIONS) { i ->
            val envelope = randomMultiDevice(random)
            val json = encode(envelope)
            val parsed = MultiDeviceEnvelopePolicy.parse(json)
            assertEquals(
                parsed != null, SignalEnvelopeJson.isEncryptedEnvelope(json),
                "fuzz 迭代 #$i：多设备信封的加密判定必须与 MultiDevice 解析一致",
            )
            assertEquals(
                parsed?.payloadType, SignalEnvelopeJson.envelopePayloadType(json),
                "fuzz 迭代 #$i：多设备 payloadType 必须与 MultiDevice 解析一致",
            )
            assertNull(
                SignalEnvelopeJson.parseEncryptedMessageEnvelope(json),
                "fuzz 迭代 #$i：多设备信封顶层无 ciphertext，解不成单设备信封",
            )
            assertFalse(SignalEnvelopeJson.isSenderKeyEnvelope(json), "fuzz 迭代 #$i：多设备信封不是 SenderKey 信封")
            assertFalse(
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json),
                "fuzz 迭代 #$i：多设备信封不是分发信封",
            )
            assertNull(
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(json),
                "fuzz 迭代 #$i：多设备信封无 SenderKey epoch",
            )
        }
    }

    @Test
    fun `handwritten multi device json pins the policy edge cases`() {
        val base = "\"senderDeviceId\":1,\"payloadType\":\"TEXT\"," +
            "\"entries\":[{\"recipientDeviceId\":2,\"ciphertext\":\"Y2lwaGVy\"}]"
        assertTrue(SignalEnvelopeJson.isEncryptedEnvelope("{$base}"))
        assertEquals("TEXT", SignalEnvelopeJson.envelopePayloadType("{$base}"))
        // 省略 recipientDeviceId 的紧凑历史信封仍可识别。
        assertTrue(
            SignalEnvelopeJson.isEncryptedEnvelope(
                "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\"," +
                    "\"entries\":[{\"ciphertext\":\"Y2lwaGVy\"}]}",
            ),
        )
        // version 非 0/3、多设备算法不对、entries 为空、密文空白、deviceId 越界均不识别。
        assertFalse(SignalEnvelopeJson.isEncryptedEnvelope("{\"version\":2,$base}"))
        assertFalse(SignalEnvelopeJson.isEncryptedEnvelope("{\"version\":3,\"algorithm\":\"bogus\",$base}"))
        assertFalse(
            SignalEnvelopeJson.isEncryptedEnvelope(
                "{\"senderDeviceId\":1,\"entries\":[]}",
            ),
        )
        assertFalse(
            SignalEnvelopeJson.isEncryptedEnvelope(
                "{\"senderDeviceId\":1,\"entries\":[{\"recipientDeviceId\":2,\"ciphertext\":\"\"}]}",
            ),
        )
        assertFalse(
            SignalEnvelopeJson.isEncryptedEnvelope(
                "{\"senderDeviceId\":1,\"entries\":[{\"recipientDeviceId\":300,\"ciphertext\":\"abc\"}]}",
            ),
        )
    }

    @Test
    fun `mutated envelope json stays cross-consistent`() {
        val random = Random(0xA2_2065)
        val valid = listOf(
            encode(randomEncrypted(random)),
            encode(randomSenderKey(random)),
            encode(randomDistribution(random)),
            encode(randomMultiDevice(random)),
        )
        repeat(ITERATIONS) { i ->
            val original = valid.random(random)
            val mutated = when (random.nextInt(0, 4)) {
                0 -> original.take(random.nextInt(0, original.length + 1))
                1 -> original.map { c ->
                    if (random.nextInt(0, 20) == 0) GARBAGE_PARTS.random(random).first() else c
                }.joinToString("")
                2 -> original.replaceFirst("{", "{\"unknownKey\":123,")
                else -> original
                    .replace("\"version\":2", "\"version\":\"2\"")
                    .replace("\"version\":1", "\"version\":\"x\"")
            }
            val multi = MultiDeviceEnvelopePolicy.parse(mutated)
            val encrypted = SignalEnvelopeJson.parseEncryptedMessageEnvelope(mutated)
            assertEquals(
                multi != null || (encrypted?.version ?: 0) >= 1,
                SignalEnvelopeJson.isEncryptedEnvelope(mutated),
                "fuzz 迭代 #$i：isEncryptedEnvelope 必须与两路解析一致",
            )
            assertEquals(
                multi?.payloadType ?: encrypted?.payloadType,
                SignalEnvelopeJson.envelopePayloadType(mutated),
                "fuzz 迭代 #$i：payloadType 必须与两路解析一致",
            )
            val senderKey = SignalEnvelopeJson.parseSenderKeyMessageEnvelope(mutated)
            assertEquals(
                senderKey?.let {
                    it.version == SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION &&
                        it.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY
                } == true,
                SignalEnvelopeJson.isSenderKeyEnvelope(mutated),
                "fuzz 迭代 #$i：isSenderKeyEnvelope 必须与解析结果一致",
            )
            assertEquals(
                senderKey?.epoch?.takeIf { it > 0L },
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(mutated),
                "fuzz 迭代 #$i：epoch 必须与解析结果一致",
            )
            val distribution = SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(mutated)
            assertEquals(
                distribution?.let {
                    it.version == SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION &&
                        it.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION
                } == true,
                SignalEnvelopeJson.isSenderKeyDistributionEnvelope(mutated),
                "fuzz 迭代 #$i：isSenderKeyDistributionEnvelope 必须与解析结果一致",
            )
        }
    }

    @Test
    fun `build helpers produce recognizable envelopes`() {
        val random = Random(0xA2_2066)
        repeat(ITERATIONS) { i ->
            val epoch = listOf(0L, 1L, 7L).random(random)
            val senderKeyJson = SignalEnvelopeJson.buildUnsupportedSenderKeyEnvelope(
                groupId = randomText(random),
                distributionId = randomText(random),
                payloadType = "TEXT",
                ciphertext = randomCiphertext(random),
                epoch = epoch,
            )
            assertTrue(SignalEnvelopeJson.isSenderKeyEnvelope(senderKeyJson), "fuzz 迭代 #$i：build 的 SenderKey 信封必须可识别")
            assertEquals(
                epoch.takeIf { it > 0L },
                SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(senderKeyJson),
                "fuzz 迭代 #$i：build 的 epoch 必须原样返回",
            )
            val encryptedJson = SignalEnvelopeJson.buildEncryptedMessageEnvelope(
                senderDeviceId = random.nextInt(0, 300),
                recipientDeviceId = if (random.nextBoolean()) null else random.nextInt(0, 300),
                ciphertextType = CIPHERTEXT_TYPES.random(random),
                payloadType = PAYLOAD_TYPES.random(random),
                ciphertext = randomCiphertext(random),
            )
            assertTrue(SignalEnvelopeJson.isEncryptedEnvelope(encryptedJson), "fuzz 迭代 #$i：build 的加密信封必须可识别")
            assertEquals(
                SignalEnvelopeJson.parseEncryptedMessageEnvelope(encryptedJson)?.payloadType,
                SignalEnvelopeJson.envelopePayloadType(encryptedJson),
                "fuzz 迭代 #$i：build 的 payloadType 必须一致",
            )
            val multiJson = SignalEnvelopeJson.buildMultiDeviceMessageEnvelope(
                senderDeviceId = 1,
                payloadType = "TEXT",
                entries = listOf(
                    MultiDeviceMessageEntry(recipientDeviceId = 2, ciphertext = "Y2lwaGVy"),
                ),
            )
            assertTrue(SignalEnvelopeJson.isEncryptedEnvelope(multiJson), "fuzz 迭代 #$i：build 的多设备信封必须可识别")
            assertEquals("TEXT", SignalEnvelopeJson.envelopePayloadType(multiJson), "fuzz 迭代 #$i：build 的多设备 payloadType")
        }
    }
}
