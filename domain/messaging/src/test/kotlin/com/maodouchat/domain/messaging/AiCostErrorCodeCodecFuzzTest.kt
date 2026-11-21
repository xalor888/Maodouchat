package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AiCostErrorCodeCodec 的 fuzz：`基码:秒数` 后缀编解码的往返恒等与边界钉住。
// 只加测试、零生产改动；断言依据是文件头注释的契约（比较只看基码、秒数只跟
// RATE_LIMITED / QUOTA_EXCEEDED / RATE_LIMIT* 走、秒数 >0 且上限 86400）。
class AiCostErrorCodeCodecFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
        private const val MAX_SECONDS = 86_400L
    }

    // 按契约独立推导的期望基码：首个 ':' 之前的部分，trim 后转大写；空串保持空。
    private fun expectedBase(raw: String?): String {
        val trimmed = (raw ?: "").trim()
        if (trimmed.isEmpty()) return ""
        val head = trimmed.substringBefore(':').trim().uppercase()
        return head.ifBlank { trimmed.uppercase() }
    }

    private fun secondsQualify(cleanBase: String): Boolean =
        cleanBase == AiCostVisibilityPolicy.ERROR_RATE_LIMIT ||
            cleanBase == AiCostVisibilityPolicy.ERROR_QUOTA ||
            cleanBase.startsWith("RATE_LIMIT")

    private fun randomBase(random: Random): String = when (random.nextInt(10)) {
        0 -> "RATE_LIMITED"
        1 -> "rate_limited"
        2 -> "  RATE_LIMITED  "
        3 -> "QUOTA_EXCEEDED"
        4 -> "quota_exceeded"
        5 -> "RATE_LIMIT"
        6 -> "RATE_LIMIT_X"
        7 -> "OTHER_ERROR"
        8 -> ""
        else -> randomToken(random, random.nextInt(1, 12))
    }

    private fun randomToken(random: Random, length: Int): String {
        val alphabet = "abcXYZ019_:.- "
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomSeconds(random: Random): Long? = when (random.nextInt(8)) {
        0 -> null
        1 -> 0L
        2 -> -5L
        3 -> 1L
        4 -> 45L
        5 -> 86_399L
        6 -> 86_400L
        else -> random.nextLong(-100, 200_000)
    }

    @Test
    fun `encode then parse round-trips on seeded random bases and seconds`() {
        val random = Random(31001)
        repeat(ITERATIONS) { i ->
            val base = randomBase(random)
            val seconds = randomSeconds(random)
            val clean = expectedBase(base)
            val clamped = seconds?.takeIf { it > 0L }?.coerceAtMost(MAX_SECONDS)
            val expectedEncoded = if (clamped != null && secondsQualify(clean)) {
                "$clean:$clamped"
            } else {
                clean
            }
            val encoded = AiCostErrorCodeCodec.encodeErrorCode(base, seconds)
            assertEquals(expectedEncoded, encoded, "iteration $i: encoded form must match contract")
            assertEquals(clean, AiCostErrorCodeCodec.baseErrorCode(encoded), "iteration $i: base must survive encode")
            val expectedParsed = if (clamped != null && secondsQualify(clean)) clamped else null
            assertEquals(
                expectedParsed,
                AiCostErrorCodeCodec.embeddedRetryAfterSeconds(encoded),
                "iteration $i: seconds must survive encode only for qualifying bases"
            )
        }
    }

    @Test
    fun `base and seconds never throw on seeded random garbage`() {
        val random = Random(31002)
        repeat(200) { i ->
            val raw = when (random.nextInt(4)) {
                0 -> randomToken(random, random.nextInt(0, 40))
                1 -> random.nextLong(-1000, 1000).toString()
                2 -> "x".repeat(random.nextInt(0, 120))
                else -> randomToken(random, random.nextInt(0, 8)) + ":" + randomToken(random, random.nextInt(0, 8))
            }
            val base = AiCostErrorCodeCodec.baseErrorCode(raw)
            assertTrue(base == base.uppercase(), "iteration $i: base must be uppercased")
            val seconds = AiCostErrorCodeCodec.embeddedRetryAfterSeconds(raw)
            assertTrue(seconds == null || (seconds in 1..MAX_SECONDS), "iteration $i: seconds must be null or 1..86400")
        }
        assertEquals("", AiCostErrorCodeCodec.baseErrorCode(null))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds(null))
    }

    @Test
    fun `base normalization is pinned`() {
        assertEquals("", AiCostErrorCodeCodec.baseErrorCode(""))
        assertEquals("", AiCostErrorCodeCodec.baseErrorCode("   "))
        assertEquals("RATE_LIMITED", AiCostErrorCodeCodec.baseErrorCode("rate_limited"))
        assertEquals("RATE_LIMITED", AiCostErrorCodeCodec.baseErrorCode("RATE_LIMITED:45"))
        assertEquals(":", AiCostErrorCodeCodec.baseErrorCode(":"))
        assertEquals(":", AiCostErrorCodeCodec.baseErrorCode(" : "))
        assertEquals("A", AiCostErrorCodeCodec.baseErrorCode("a:b:c"))
        assertEquals("RATE_LIMITED", AiCostErrorCodeCodec.baseErrorCode("  rate_limited : 45 "))
    }

    @Test
    fun `embedded seconds parsing is pinned`() {
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds(""))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:"))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds(":45"))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:0"))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:-5"))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:abc"))
        assertNull(AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:45:60"))
        assertEquals(45L, AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:45"))
        assertEquals(86_400L, AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE:86401"))
        assertEquals(60L, AiCostErrorCodeCodec.embeddedRetryAfterSeconds("RATE: 60 "))
    }

    @Test
    fun `encode suffix rules are pinned`() {
        assertEquals("RATE_LIMITED:45", AiCostErrorCodeCodec.encodeErrorCode("RATE_LIMITED", 45))
        assertEquals("RATE_LIMITED:45", AiCostErrorCodeCodec.encodeErrorCode("rate_limited", 45))
        assertEquals("QUOTA_EXCEEDED:86400", AiCostErrorCodeCodec.encodeErrorCode("QUOTA_EXCEEDED", 86401))
        assertEquals("RATE_LIMIT_X:30", AiCostErrorCodeCodec.encodeErrorCode("RATE_LIMIT_X", 30))
        assertEquals("OTHER", AiCostErrorCodeCodec.encodeErrorCode("OTHER", 30))
        assertEquals("OTHER", AiCostErrorCodeCodec.encodeErrorCode("OTHER", null))
        assertEquals("RATE_LIMITED", AiCostErrorCodeCodec.encodeErrorCode("RATE_LIMITED", 0))
        assertEquals("RATE_LIMITED", AiCostErrorCodeCodec.encodeErrorCode("RATE_LIMITED", -5))
        assertEquals("", AiCostErrorCodeCodec.encodeErrorCode("", null))
        assertEquals("", AiCostErrorCodeCodec.encodeErrorCode("  ", 45))
        assertEquals("FOO", AiCostErrorCodeCodec.encodeErrorCode("FOO:BAR", 45))
    }
}
