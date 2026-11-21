package com.maodouchat.domain.messaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EncryptedAttachmentReferenceCodecFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val ID_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        private const val BASE64_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val HEX_CHARSET = "0123456789abcdef"
        private const val GARBAGE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_{}\":,./\\-_=+ \n\t@：中文"
        private const val FILENAME_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._- /:*?\"<>|\n\t"
        private val KNOWN_FIELDS = listOf(
            "kind", "attachmentId", "keyBase64", "ivBase64", "cipherSha256", "plainSha256",
            "cipherSize", "fileName", "mimeType", "plainSize", "durationMs"
        )
        private val MIME_TYPES = listOf(
            "image/jpeg", "APPLICATION/PDF", "Text/Plain", "audio/MPEG", "VIDEO/mp4",
            "application/octet-stream", "X-Custom/Type"
        )
    }

    private fun randomText(random: Random, alphabet: String, minLength: Int, maxLength: Int): String {
        val length = random.nextInt(minLength, maxLength + 1)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun validRef(
        attachmentId: String = "att_" + "a".repeat(30),
        keyBase64: String = "k".repeat(44),
        ivBase64: String = "i".repeat(24),
        cipherSha256: String = "c".repeat(64),
        plainSha256: String = "e".repeat(64),
        cipherSize: Long = 1_056L,
        fileName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        plainSize: Long = 1_000L,
        durationMs: Long? = null,
        kind: String = "maodouchat-attachment-v1",
    ) = EncryptedAttachmentReference(
        kind = kind,
        attachmentId = attachmentId,
        keyBase64 = keyBase64,
        ivBase64 = ivBase64,
        cipherSha256 = cipherSha256,
        plainSha256 = plainSha256,
        cipherSize = cipherSize,
        fileName = fileName,
        mimeType = mimeType,
        plainSize = plainSize,
        durationMs = durationMs,
    )

    private fun randomValidRef(random: Random): EncryptedAttachmentReference {
        val mimeType = MIME_TYPES[random.nextInt(MIME_TYPES.size)]
        val fileName = "file" + randomText(random, FILENAME_ALPHABET, 0, 100)
        return validRef(
            attachmentId = "att_" + randomText(random, ID_CHARSET, 20, 100),
            keyBase64 = randomText(random, BASE64_CHARSET, 40, 48),
            ivBase64 = randomText(random, BASE64_CHARSET, 16, 24),
            cipherSha256 = randomText(random, HEX_CHARSET, 64, 64),
            plainSha256 = randomText(random, HEX_CHARSET, 64, 64),
            cipherSize = random.nextLong(17L, 10_000_000L),
            fileName = fileName,
            mimeType = mimeType,
            plainSize = random.nextLong(1L, 10_000_000L),
            durationMs = if (random.nextBoolean()) null else random.nextLong(500L, 3_600_001L),
        )
    }

    // 生产侧 sanitizeFileName 的复刻（迷你模型）：非法字符变 _、去首尾空白与点、截 120、空则 file。
    private fun expectedSanitizedFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "file" }
    }

    private fun assertDecodedIsNullOrEncodable(raw: String, iteration: Int) {
        val decoded = decodeEncryptedAttachmentReference(raw)
        if (decoded != null) {
            assertEquals(
                "maodouchat-attachment-v1",
                decoded.kind,
                "fuzz 迭代 #$iteration：解出的引用 kind 必须合法，raw=$raw"
            )
            encodeEncryptedAttachmentReference(decoded)
        }
    }

    @Test
    fun `random garbage never throws on decode`() {
        val random = Random(0x66_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            // 长度跨过 2048 上限，覆盖“超长直接回 null”分支。
            val raw = randomText(random, GARBAGE_ALPHABET, 0, 3000)
            assertDecodedIsNullOrEncodable(raw, i)
        }
    }

    @Test
    fun `random structured json objects never throw on decode`() {
        val random = Random(0x67_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val fieldCount = random.nextInt(0, 6)
            val obj = JsonObject(
                (0 until fieldCount).associate {
                    val name = if (random.nextBoolean()) {
                        KNOWN_FIELDS[random.nextInt(KNOWN_FIELDS.size)]
                    } else {
                        "unknown" + random.nextInt(100)
                    }
                    val value = when (random.nextInt(5)) {
                        0 -> JsonPrimitive(random.nextBoolean())
                        1 -> JsonPrimitive(random.nextInt(-1000, 1001))
                        2 -> JsonPrimitive(randomText(random, ID_CHARSET, 12))
                        3 -> JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x")))
                        else -> JsonNull
                    }
                    name to value
                }
            )
            val raw = Json.encodeToString(JsonObject.serializer(), obj)
            assertDecodedIsNullOrEncodable(raw, i)
        }
    }

    @Test
    fun `random valid references round trip with sanitization`() {
        val random = Random(0x68_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val ref = randomValidRef(random)
            val expected = ref.copy(
                fileName = expectedSanitizedFileName(ref.fileName),
                mimeType = ref.mimeType.lowercase().take(100)
            )
            val decoded = decodeEncryptedAttachmentReference(encodeEncryptedAttachmentReference(ref))
            assertEquals(expected, decoded, "fuzz 迭代 #$i：合法引用往返必须恒等（文件名消毒 + mime 小写）")
        }
    }

    @Test
    fun `boundary lengths flip encode success`() {
        val random = Random(0x69_2026)
        // 固定边界表：左值必须被拒、右值必须通过。
        val badRefs = listOf(
            "keyBase64 39 位" to validRef(keyBase64 = "k".repeat(39)),
            "keyBase64 49 位" to validRef(keyBase64 = "k".repeat(49)),
            "ivBase64 15 位" to validRef(ivBase64 = "i".repeat(15)),
            "ivBase64 25 位" to validRef(ivBase64 = "i".repeat(25)),
            "attachmentId 后缀 19 位" to validRef(attachmentId = "att_" + "a".repeat(19)),
            "attachmentId 后缀 101 位" to validRef(attachmentId = "att_" + "a".repeat(101)),
            "cipherSha256 大写" to validRef(cipherSha256 = "C".repeat(64)),
            "cipherSha256 含 g" to validRef(cipherSha256 = "g".repeat(64)),
            "cipherSha256 63 位" to validRef(cipherSha256 = "c".repeat(63)),
            "cipherSha256 65 位" to validRef(cipherSha256 = "c".repeat(65)),
            "cipherSize 16" to validRef(cipherSize = 16L),
            "plainSize 0" to validRef(plainSize = 0L),
            "durationMs 499" to validRef(durationMs = 499L),
            "durationMs 3600001" to validRef(durationMs = 3_600_001L),
            "mimeType 101 位" to validRef(mimeType = "a".repeat(101)),
            "fileName 全空白" to validRef(fileName = "   "),
            "kind 错" to validRef(kind = "other-kind"),
        )
        for ((label, ref) in badRefs) {
            assertFailsWith<AttachmentCryptoException>("$label 必须被拒") {
                encodeEncryptedAttachmentReference(ref)
            }
        }
        val goodRefs = listOf(
            "keyBase64 40 位" to validRef(keyBase64 = "k".repeat(40)),
            "keyBase64 48 位" to validRef(keyBase64 = "k".repeat(48)),
            "ivBase64 16 位" to validRef(ivBase64 = "i".repeat(16)),
            "ivBase64 24 位" to validRef(ivBase64 = "i".repeat(24)),
            "attachmentId 后缀 20 位" to validRef(attachmentId = "att_" + "a".repeat(20)),
            "attachmentId 后缀 100 位" to validRef(attachmentId = "att_" + "a".repeat(100)),
            "cipherSize 17" to validRef(cipherSize = 17L),
            "plainSize 1" to validRef(plainSize = 1L),
            "durationMs 500" to validRef(durationMs = 500L),
            "durationMs 3600000" to validRef(durationMs = 3_600_000L),
            "mimeType 100 位" to validRef(mimeType = "a".repeat(100)),
        )
        for ((label, ref) in goodRefs) {
            assertNotNull(
                decodeEncryptedAttachmentReference(encodeEncryptedAttachmentReference(ref)),
                "$label 必须通过"
            )
        }
        // 文件名全是点：编码前非空白可通过，消毒后变 file。
        val dots = validRef(fileName = "...")
        assertEquals(
            "file",
            decodeEncryptedAttachmentReference(encodeEncryptedAttachmentReference(dots))?.fileName,
            "文件名全是点必须消毒为 file"
        )
        // 随机边界抖动：长度在边界 ±1 内翻转时，编码成败必须与校验一致。
        repeat(STRUCTURED_ITERATIONS) { i ->
            val ref = when (random.nextInt(4)) {
                0 -> validRef(keyBase64 = "k".repeat(random.nextInt(38, 51)))
                1 -> validRef(ivBase64 = "i".repeat(random.nextInt(14, 27)))
                2 -> validRef(attachmentId = "att_" + "a".repeat(random.nextInt(18, 103)))
                else -> validRef(durationMs = random.nextLong(495L, 3_600_005L))
            }
            val ok = runCatching { encodeEncryptedAttachmentReference(ref) }.isSuccess
            val expectedOk = ref.keyBase64.length in 40..48 &&
                ref.ivBase64.length in 16..24 &&
                ref.attachmentId.length - 4 in 20..100 &&
                (ref.durationMs == null || ref.durationMs in 500L..3_600_000L)
            assertEquals(expectedOk, ok, "fuzz 迭代 #$i：边界抖动的编码成败必须与校验一致，ref=$ref")
        }
    }

    @Test
    fun `tampered field values decode to null`() {
        val random = Random(0x6A_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val ref = randomValidRef(random)
            val encoded = encodeEncryptedAttachmentReference(ref)
            val obj = Json.parseToJsonElement(encoded).jsonObject
            val tampered = when (random.nextInt(7)) {
                0 -> obj + ("attachmentId" to JsonPrimitive("bad-id"))
                1 -> obj + ("kind" to JsonPrimitive("tampered"))
                2 -> obj + ("keyBase64" to JsonPrimitive("short"))
                3 -> obj + ("cipherSize" to JsonPrimitive(16))
                4 -> obj + ("durationMs" to JsonPrimitive(100))
                5 -> obj + ("mimeType" to JsonPrimitive(""))
                else -> obj + ("fileName" to JsonPrimitive(""))
            }
            val raw = Json.encodeToString(JsonObject.serializer(), JsonObject(tampered))
            assertNull(
                decodeEncryptedAttachmentReference(raw),
                "fuzz 迭代 #$i：篡改字段必须解出 null，raw=$raw"
            )
        }
    }

    @Test
    fun `unknown keys are ignored on decode`() {
        val random = Random(0x6B_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val ref = randomValidRef(random)
            val encoded = encodeEncryptedAttachmentReference(ref)
            val obj = Json.parseToJsonElement(encoded).jsonObject
            val extras = (0 until random.nextInt(0, 4)).associate {
                "extra$it" to JsonPrimitive(random.nextInt(1000))
            }
            val raw = Json.encodeToString(JsonObject.serializer(), JsonObject(obj + extras))
            val expected = ref.copy(
                fileName = expectedSanitizedFileName(ref.fileName),
                mimeType = ref.mimeType.lowercase().take(100)
            )
            assertEquals(expected, decodeEncryptedAttachmentReference(raw), "fuzz 迭代 #$i：未知键必须被忽略")
        }
    }
}
