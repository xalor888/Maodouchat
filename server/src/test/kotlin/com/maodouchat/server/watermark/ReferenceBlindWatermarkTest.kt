package com.maodouchat.server.watermark

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G187b：`ReferenceBlindWatermark` —— blind_watermark Python 库的 Kotlin 忠实移植。
 *
 * 565 行、`object` 单例 + 两个公开纯函数、**零 IO**（只用 kotlin.math 与
 * java.util.Random）。算法链：BGR→YUV→Haar DWT 取 LL → 4×4 分块 →
 * 每块 DCT → 按 password_img 置换 → SVD → s[0]/s[1] 量化嵌入（d1=36/d2=20）→
 * 重建 → 逆置换 → IDCT；位流先用 password_wm 洗牌；提取反向 + 3 通道求平均 +
 * 一维 kmeans + 逆洗牌。
 *
 * 核心硬证据只有一个：**embed 后必须能 extract 回逐字节相同的 payload**。
 * 其余用例守边界：入参、容量、密码隔离、原数组不变性、无水印判定。
 *
 * 注：`AdminWatermarkExtractorTest` 覆盖的是另一条路径（FrequencyWatermark/DCT-QIM），
 * 与本文件测的 ReferenceBlindWatermark 无重叠。
 */
class ReferenceBlindWatermarkTest {

    /** 确定性像素：不用随机，保证失败可复现。 */
    private fun pixelsOf(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or ((x * 31 + y * 17) and 0xFF shl 16) or
                ((x * 13 + y * 29) and 0xFF shl 8) or
                ((x * 7 + y * 11) and 0xFF)
        }

    private fun extractArgb(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = (0xFF shl 24) or (p and 0x00FFFFFF)
        }
        return out
    }

    // ---- 往返一致性（核心硬证据）----

    @Test
    fun `embed then extract round trips the payload`() {
        val w = 128
        val h = 128
        val payload = "hello".toByteArray(Charsets.US_ASCII)   // 5 字节 = 40 位
        val embedded = ReferenceBlindWatermark.embedPixels(
            pixelsOf(w, h), w, h, payload,
            passwordWm = 1L, passwordImg = 1L,
        )
        val extracted = ReferenceBlindWatermark.extractPayload(
            embedded, w, h, payload.size * 8,
            passwordWm = 1L, passwordImg = 1L,
        )
        assertNotNull(extracted, "往返提取不应返回 null")
        assertContentEquals(payload, extracted, "提取结果必须逐字节等于原 payload")
    }

    @Test
    fun `a single byte payload round trips`() {
        val w = 128
        val h = 128
        val payload = byteArrayOf(0x5A)
        val embedded = ReferenceBlindWatermark.embedPixels(pixelsOf(w, h), w, h, payload)
        val extracted = ReferenceBlindWatermark.extractPayload(embedded, w, h, 8)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted)
    }

    @Test
    fun `all byte values survive the round trip`() {
        val w = 128
        val h = 128
        // 0x00..0xFF 全谱，8 字节 = 64 位
        val payload = ByteArray(8) { it.toByte() }
        val embedded = ReferenceBlindWatermark.embedPixels(pixelsOf(w, h), w, h, payload)
        val extracted = ReferenceBlindWatermark.extractPayload(embedded, w, h, payload.size * 8)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted, "0x00 与 0xFF 两端都必须正确")
    }

    @Test
    fun `a larger image carries a longer payload`() {
        val w = 256
        val h = 256
        val payload = ByteArray(16) { (it * 37 + 11).toByte() }
        val embedded = ReferenceBlindWatermark.embedPixels(pixelsOf(w, h), w, h, payload)
        val extracted = ReferenceBlindWatermark.extractPayload(embedded, w, h, payload.size * 8)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted)
    }

    // ---- 入参校验 ----

    @Test
    fun `a pixel array that does not match the dimensions is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ReferenceBlindWatermark.embedPixels(IntArray(100), 10, 11, byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            ReferenceBlindWatermark.extractPayload(IntArray(100), 10, 11, 8)
        }
    }

    @Test
    fun `a payload bit count that is not a byte multiple is rejected`() {
        val w = 128
        val h = 128
        val embedded = ReferenceBlindWatermark.embedPixels(pixelsOf(w, h), w, h, byteArrayOf(1))
        assertNull(ReferenceBlindWatermark.extractPayload(embedded, w, h, 0), "0 位必须返回 null")
        assertNull(ReferenceBlindWatermark.extractPayload(embedded, w, h, -8), "负数必须返回 null")
        assertNull(ReferenceBlindWatermark.extractPayload(embedded, w, h, 7), "非 8 倍数必须返回 null")
    }

    // ---- 容量边界 ----

    @Test
    fun `an image too small to carry the payload is returned untouched`() {
        val w = 32
        val h = 32
        val original = pixelsOf(w, h)
        val snapshot = original.copyOf()
        val payload = ByteArray(64) { 0x7F }   // 512 位，远超 32×32 的容量
        val result = ReferenceBlindWatermark.embedPixels(original, w, h, payload)
        assertContentEquals(snapshot, result, "容量不足必须原样返回，不得改动任何像素")
        assertNull(
            ReferenceBlindWatermark.extractPayload(result, w, h, payload.size * 8),
            "没嵌进去的 payload 不应被提取出来",
        )
    }

    @Test
    fun `extract refuses a bit count beyond the block capacity`() {
        val w = 32
        val h = 32
        // blockNum <= payloadBitCount 时必须返回 null，而不是硬算
        assertNull(ReferenceBlindWatermark.extractPayload(pixelsOf(w, h), w, h, 4096))
    }

    // ---- 原数组不变性 ----

    @Test
    fun `embed does not mutate the caller's pixel array`() {
        val w = 128
        val h = 128
        val original = pixelsOf(w, h)
        val snapshot = original.copyOf()
        ReferenceBlindWatermark.embedPixels(original, w, h, "abc".toByteArray(Charsets.US_ASCII))
        assertContentEquals(snapshot, original, "注释声称原数组不变，必须验证")
    }

    // ---- 密码隔离 ----

    @Test
    fun `a payload embedded with one password does not read back with another`() {
        val w = 128
        val h = 128
        val payload = "secret".toByteArray(Charsets.US_ASCII)
        val embedded = ReferenceBlindWatermark.embedPixels(
            pixelsOf(w, h), w, h, payload,
            passwordWm = 1L, passwordImg = 1L,
        )
        // 用错 passwordWm：位序被洗乱，提取结果不应等于原 payload
        val wrongWm = ReferenceBlindWatermark.extractPayload(
            embedded, w, h, payload.size * 8,
            passwordWm = 2L, passwordImg = 1L,
        )
        if (wrongWm != null) {
            assertFalse(
                payload.contentEquals(wrongWm),
                "错密码不得读回原 payload（可能返回乱码或 null，两者都记录在案）",
            )
        }
        // 用错 passwordImg：分块打乱方式不同
        val wrongImg = ReferenceBlindWatermark.extractPayload(
            embedded, w, h, payload.size * 8,
            passwordWm = 1L, passwordImg = 2L,
        )
        if (wrongImg != null) {
            assertFalse(
                payload.contentEquals(wrongImg),
                "错 passwordImg 也不得读回原 payload",
            )
        }
        // 而正确密码仍然读得回
        val right = ReferenceBlindWatermark.extractPayload(
            embedded, w, h, payload.size * 8,
            passwordWm = 1L, passwordImg = 1L,
        )
        assertNotNull(right)
        assertContentEquals(payload, right, "正确密码必须始终读得回")
    }

    // ---- 无水印判定 ----

    @Test
    fun `a clean gradient image reports no watermark`() {
        val w = 128
        val h = 128
        // 平滑渐变：从不嵌入任何水印
        val clean = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x80
        }
        val extracted = ReferenceBlindWatermark.extractPayload(clean, w, h, 40)
        // 置信度门 centers[1]-centers[0] < 0.6 应判无水印
        assertNull(extracted, "干净图必须判无水印返回 null（而不是提出全 1/全 0 假阳性）")
    }

    // ---- 位流工具 ----

    @Test
    fun `bytes to bits and back is the identity`() {
        val samples = listOf(
            byteArrayOf(0),
            byteArrayOf(1),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0x5A),
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            ByteArray(16) { (it * 31).toByte() },
        )
        samples.forEach { bytes ->
            val bits = ReferenceBlindWatermark.bytesToBits(bytes)
            assertEquals(bytes.size * 8, bits.size, "每个字节必须展开成 8 位")
            assertContentEquals(bytes, ReferenceBlindWatermark.bitsToBytes(bits), "往返必须恒等")
        }
    }

    @Test
    fun `bits to bytes rejects a length that is not a byte multiple`() {
        assertFailsWith<IllegalArgumentException> {
            ReferenceBlindWatermark.bitsToBytes(BooleanArray(7))
        }
    }

    @Test
    fun `bit order is most significant first`() {
        // 0x80 = 1000 0000：只有最高位是 1
        assertTrue(ReferenceBlindWatermark.bytesToBits(byteArrayOf(0x80.toByte()))[0])
        // 0x01 = 0000 0001：只有最低位是 1
        val low = ReferenceBlindWatermark.bytesToBits(byteArrayOf(0x01))
        assertTrue(low[7], "最低位必须落在第 8 个 bit（MSB first）")
        assertFalse(low[0])
    }
}
