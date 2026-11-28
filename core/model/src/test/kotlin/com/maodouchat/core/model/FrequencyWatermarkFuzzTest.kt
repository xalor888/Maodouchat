package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [FrequencyWatermark] (checklist line 931, 34th block).
 * Test-only, zero production changes.
 *
 * Oracles are independent transcriptions of the production arithmetic:
 * - [buildPayload] is FNV-1a-64 over the three fields in order, with a 0x2F
 *   separator mixed after each non-empty field; the low 48 bits are taken
 *   little-endian. The oracle below re-derives the hash from the FNV
 *   definition rather than re-running production code.
 * - [decodePayloadHex] is plain lowercase hex.
 * - embed/extract round-trip is a mathematical identity of the QIM scheme
 *   (DCT round-trip error < 1e-6 per [FrequencyWatermarkTest]); the fuzz
 *   image generator uses a 96 pedestal so no pixel sits near the 0/255
 *   clamp, which keeps the round-trip exact even at 128x128 (4 votes/tile).
 * A Python mini-model differentially checks the FNV + hex oracles on 5000
 * random strings/payloads (0 diffs) before this file was written.
 */
class FrequencyWatermarkFuzzTest {

    private val hexDigits = "0123456789abcdef"

    private fun oraclePayload(userId: String?, chatId: String?, deviceHint: String?): ByteArray {
        var hash = 0xCBF29CE484222325UL
        val prime = 0x100000001B3UL
        fun mix(s: String?) {
            if (s.isNullOrEmpty()) return
            for (b in s.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toLong() and 0xFFL).toULong()
                hash *= prime
            }
            hash = hash xor 0x2FUL
            hash *= prime
        }
        mix(userId)
        mix(chatId)
        mix(deviceHint)
        val low48 = hash and 0xFFFFFFFFFFFFUL
        return ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { i ->
            ((low48 shr (i * 8)) and 0xFFUL).toByte()
        }
    }

    private fun oracleHex(payload: ByteArray): String {
        val sb = StringBuilder(payload.size * 2)
        for (b in payload) {
            val v = b.toInt() and 0xFF
            sb.append(hexDigits[v ushr 4]).append(hexDigits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun randomString(r: Random): String? {
        if (r.nextInt(10) == 0) return null
        if (r.nextInt(10) == 0) return ""
        // Whole graphemes (emoji kept paired) so UTF-8 encoding is well-defined.
        val alphabet = listOf(
            "a", "b", "c", "X", "Y", "Z", "0", "1", "9", "-", " ", "_", "中", "文", "\ud83d\ude00"
        )
        return buildString {
            repeat(r.nextInt(40)) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    /** Pedestal-96 synth image: gradient + small noise, far from the 0/255 clamps. */
    private fun synthImage(r: Random, w: Int, h: Int): FloatArray {
        val luma = FloatArray(w * h)
        var s = r.nextLong()
        fun rnd(): Float {
            s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL
            return (s % 16).toFloat()
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val grad = (x + y) * 0.25f
                luma[y * w + x] = (96f + grad + rnd()).coerceIn(0f, 255f)
            }
        }
        return luma
    }

    @Test
    fun buildPayloadMatchesFNV1aOracle() {
        val r = Random(3401)
        repeat(300) {
            val userId = randomString(r)
            val chatId = randomString(r)
            val deviceHint = randomString(r)
            assertContentEquals(
                oraclePayload(userId, chatId, deviceHint),
                FrequencyWatermark.buildPayload(userId, chatId, deviceHint)
            )
        }
    }

    @Test
    fun buildPayloadTreatsNullAndEmptyFieldsIdentically() {
        val r = Random(3402)
        repeat(300) {
            // Null vs empty must hash identically (both skipped), so a
            // trailing empty field must not change the payload.
            val u = randomString(r)
            val c = randomString(r)
            assertContentEquals(
                FrequencyWatermark.buildPayload(u, null, ""),
                FrequencyWatermark.buildPayload(u, "", null)
            )
            assertContentEquals(
                FrequencyWatermark.buildPayload(u, c, null),
                FrequencyWatermark.buildPayload(u, c, "")
            )
            assertContentEquals(
                FrequencyWatermark.buildPayload(u, c, ""),
                FrequencyWatermark.buildPayload(u, c, null)
            )
        }
        assertContentEquals(
            FrequencyWatermark.buildPayload(null, null, null),
            FrequencyWatermark.buildPayload("", "", "")
        )
    }

    @Test
    fun decodePayloadHexMatchesOracle() {
        val r = Random(3403)
        repeat(300) {
            val payload = ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { r.nextInt(256).toByte() }
            assertEquals(oracleHex(payload), FrequencyWatermark.decodePayloadHex(payload))
            assertEquals(12, FrequencyWatermark.decodePayloadHex(payload).length)
        }
    }

    @Test
    fun embedThenExtractRecoversRandomPayload() {
        val r = Random(3404)
        repeat(40) {
            val w = 128
            val h = 128
            val img = synthImage(r, w, h)
            val payload = ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { r.nextInt(256).toByte() }
            val marked = FrequencyWatermark.embed(img, w, h, payload)
            val recovered = FrequencyWatermark.extract(marked, w, h)
            assertTrue(recovered != null, "extraction returned null on iteration $it")
            assertContentEquals(payload, recovered)
        }
    }

    @Test
    fun embedThenExtractRecoversBuiltPayloadAt256() {
        val r = Random(3405)
        repeat(10) {
            val w = 256
            val h = 256
            val img = synthImage(r, w, h)
            val payload = FrequencyWatermark.buildPayload(randomString(r), randomString(r), randomString(r))
            val marked = FrequencyWatermark.embed(img, w, h, payload)
            val recovered = FrequencyWatermark.extract(marked, w, h)
            assertTrue(recovered != null, "extraction returned null on iteration $it")
            assertContentEquals(payload, recovered)
        }
    }

    @Test
    fun extractSurvivesSmallAdditiveNoise() {
        val r = Random(3406)
        repeat(20) {
            val w = 128
            val h = 128
            val img = synthImage(r, w, h)
            val payload = ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { r.nextInt(256).toByte() }
            val marked = FrequencyWatermark.embed(img, w, h, payload)
            val attacked = FloatArray(marked.size) { i ->
                (marked[i] + r.nextInt(5) - 2).toFloat().coerceIn(0f, 255f)
            }
            val recovered = FrequencyWatermark.extract(attacked, w, h)
            assertTrue(recovered != null, "extraction failed under +-2 noise on iteration $it")
            assertContentEquals(payload, recovered)
        }
    }

    @Test
    fun tooSmallImageIsLeftUntouchedAndYieldsNull() {
        val r = Random(3407)
        repeat(50) {
            // 56x56 = 49 blocks < 64 minimum: embed returns a copy, extract null.
            val w = 56
            val h = 56
            val img = FloatArray(w * h) { r.nextInt(256).toFloat() }
            val payload = ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { r.nextInt(256).toByte() }
            val out = FrequencyWatermark.embed(img, w, h, payload)
            assertContentEquals(img, out)
            assertNull(FrequencyWatermark.extract(out, w, h))
        }
    }

    @Test
    fun sizeAndPayloadMismatchesThrow() {
        val img = FloatArray(128 * 128) { 128f }
        val payload = ByteArray(FrequencyWatermark.PAYLOAD_BYTES) { 0 }
        assertFailsWith<IllegalArgumentException> {
            FrequencyWatermark.embed(FloatArray(10), 128, 128, payload)
        }
        assertFailsWith<IllegalArgumentException> {
            FrequencyWatermark.extract(FloatArray(10), 128, 128)
        }
        assertFailsWith<IllegalArgumentException> {
            FrequencyWatermark.embed(img, 128, 128, ByteArray(5))
        }
        assertFailsWith<IllegalArgumentException> {
            FrequencyWatermark.decodePayloadHex(ByteArray(5))
        }
        assertFailsWith<IllegalArgumentException> {
            FrequencyWatermark.decodePayloadHex(ByteArray(7))
        }
    }
}
