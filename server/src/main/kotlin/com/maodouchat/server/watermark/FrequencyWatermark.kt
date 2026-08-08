package com.maodouchat.server.watermark

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 频域盲水印引擎：在 8x8 DCT 中频系数上做 QIM（Quantization Index Modulation）嵌入，
 * 配合 8x8 平铺循环重复编码 + **二维**同步字相位搜索，实现对 JPEG 重编码、加性噪声、
 * 中心裁剪的鲁棒提取，且对人眼基本不可见（只改亮度中频，色度不变）。
 *
 * 纯 Kotlin，无 Android 依赖，可在 JVM 单测验证：往返、嵌入->提取、鲁棒性、PSNR。
 *
 * 载荷：6 字节 = 48 位（FNV-1a 48 位哈希，足以归因泄露者）。
 * 帧结构：SYNC(16) ++ PAYLOAD(48) = 64 位，按 8x8 tile 平铺循环重复。
 *
 * 鲁棒性设计要点：
 * - Q = 32：量化步长足以抵抗 ±8 像素噪声（DCT 域噪声 RMS ≈ 0.6，远小于 Q/4 = 8）。
 * - MID_BAND 选取 JPEG 标准亮度量化表 base ≤ 16 的中频位置：Q=32 与 base=16 的
 *   量化步长互为整数倍（32 = 2×16），JPEG q50 重编码后格点完全保持；base < 16
 *   的位置偏移 ≤ Q/2 的子格距离，均在 Q/4 容差内。
 * - 8x8 平铺：块 (bx,by) 承载 frame[(by%8)*8 + (bx%8)]，任意 ≥ 8×8 块的连续子区域
 *   含完整 64 位帧；裁剪只改变块栅格起点，等效于 tile 域二维循环移位。
 * - **二维**同步字搜索：穷举 8×8 = 64 个 (dy,dx) 位移，对 16 位 SYNC 做 Hamming
 *   匹配，正确处理裁剪导致的二维旋转（一维搜索无法对齐）。
 */
object FrequencyWatermark {
    const val PAYLOAD_BYTES = 6
    const val PAYLOAD_BITS = PAYLOAD_BYTES * 8          // 48
    private const val SYNC_BITS = 16
    private const val FRAME_BITS = SYNC_BITS + PAYLOAD_BITS  // 64

    /** 16 位同步字（近似随机、自相关低，便于相位对齐）。 */
    private const val SYNC = 0x9E35

    /** QIM 量化步长。Q=32 使格点 0/±16/±32/±48 与 JPEG q50 base=16 量化格点对齐。 */
    private const val Q = 32.0

    /** 同步字允许的汉明距离，超过则判定无水印。tight=2 抑制 64 假命中。 */
    private const val SYNC_TOL = 2

    /** 嵌入所需的最少 8x8 块数（保证每帧位至少 1 次重复）。 */
    private const val MIN_BLOCKS = FRAME_BITS

    /**
     * 中频系数量化位置（行优先 (u,v)），选取 JPEG 标准亮度量化表 base ≤ 16 的位置：
     * - (1,1)=12  (1,2)=14  (2,1)=13  (2,2)=16  (3,1)=16
     * Q=32 时这些位置的 JPEG q50 量化步长 q 满足 q/2 ≤ 8 = Q/4，格点偏移在容差内；
     * 其中 base=16 的位置格点 0/16/32/48 均为 16 的倍数，JPEG 后零偏移。
     * 同时这些位置避开 DC/极低频（row 0 / col 0），降低人眼可感知度。
     */
    private val MID_BAND: IntArray = intArrayOf(
        1 * Dct8.N + 1,  // (1,1) base=12
        1 * Dct8.N + 2,  // (1,2) base=14
        2 * Dct8.N + 1,  // (2,1) base=13
        2 * Dct8.N + 2,  // (2,2) base=16
        3 * Dct8.N + 1   // (3,1) base=16
    )

    private val HEX = "0123456789abcdef".toCharArray()

    /** 由 userId/chatId/deviceHint 生成 6 字节载荷（FNV-1a 64 位取低 48 位）。 */
    fun buildPayload(userId: String?, chatId: String?, deviceHint: String?): ByteArray {
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
        val out = ByteArray(PAYLOAD_BYTES)
        for (i in 0 until PAYLOAD_BYTES) {
            out[i] = ((low48 shr (i * 8)) and 0xFFUL).toByte()
        }
        return out
    }

    fun decodePayloadHex(payload: ByteArray): String {
        require(payload.size == PAYLOAD_BYTES) { "payload must be $PAYLOAD_BYTES bytes" }
        val sb = StringBuilder(PAYLOAD_BYTES * 2)
        for (b in payload) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 在亮度平面 [luma]（0..255，行优先，length == width*height）上嵌入载荷。
     * 返回新的亮度平面；原图不变。图像过小（块数 < 64）时原样返回副本。
     *
     * 嵌入策略：8x8 平铺——块 (bx,by) 承载 frame[(by%8)*8 + (bx%8)]，
     * 使任意 ≥ 8×8 块的连续子区域都含完整 64 位帧，裁剪后只需二维循环移位对齐。
     */
    fun embed(luma: FloatArray, width: Int, height: Int, payload: ByteArray): FloatArray {
        require(luma.size == width * height) { "luma size mismatch" }
        require(payload.size == PAYLOAD_BYTES) { "payload must be $PAYLOAD_BYTES bytes" }
        val out = luma.copyOf()
        val blocksX = width / Dct8.N
        val blocksY = height / Dct8.N
        val numBlocks = blocksX * blocksY
        if (numBlocks < MIN_BLOCKS) return out

        val frame = buildFrame(payload)
        val block = DoubleArray(Dct8.BLOCK)
        val work = DoubleArray(Dct8.BLOCK)

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val bit = frame[((by % 8) * 8 + (bx % 8)) % FRAME_BITS]
                for (r in 0 until Dct8.N) {
                    val base = (by * Dct8.N + r) * width + bx * Dct8.N
                    for (c in 0 until Dct8.N) {
                        block[r * Dct8.N + c] = out[base + c].toDouble()
                    }
                }
                Dct8.forward(block, work)
                for (pos in MID_BAND) {
                    work[pos] = qimEmbed(work[pos], bit)
                }
                Dct8.inverse(work, block)
                for (r in 0 until Dct8.N) {
                    val base = (by * Dct8.N + r) * width + bx * Dct8.N
                    for (c in 0 until Dct8.N) {
                        out[base + c] = block[r * Dct8.N + c].roundToInt()
                            .coerceIn(0, 255).toFloat()
                    }
                }
            }
        }
        return out
    }

    /**
     * 从亮度平面提取载荷。无水印或无法对齐同步字时返回 null。
     *
     * 提取策略：
     * 1. 对每个 8x8 块做 DCT，对 MID_BAND 系数做 QIM 判定，多数表决得到块比特。
     * 2. 按 tile 位置 (by%8, bx%8) 聚合同 tile 的块比特多数表决 -> 64 位帧。
     * 3. **二维**循环移位搜索：穷举 8×8 = 64 个 (dy,dx)，对 16 位 SYNC 做 Hamming
     *    匹配。裁剪只改变块栅格起点，等效于 tile 域 (dy,dx) 二维循环移位；
     *    一维搜索无法对齐二维旋转，必须用二维搜索。
     */
    fun extract(luma: FloatArray, width: Int, height: Int): ByteArray? {
        require(luma.size == width * height) { "luma size mismatch" }
        val blocksX = width / Dct8.N
        val blocksY = height / Dct8.N
        val numBlocks = blocksX * blocksY
        if (numBlocks < MIN_BLOCKS) return null

        val block = DoubleArray(Dct8.BLOCK)
        val work = DoubleArray(Dct8.BLOCK)
        val tileVotesOn = IntArray(FRAME_BITS)
        val tileVotesOff = IntArray(FRAME_BITS)
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                for (r in 0 until Dct8.N) {
                    val base = (by * Dct8.N + r) * width + bx * Dct8.N
                    for (c in 0 until Dct8.N) {
                        block[r * Dct8.N + c] = luma[base + c].toDouble()
                    }
                }
                Dct8.forward(block, work)
                var votes1 = 0
                for (pos in MID_BAND) {
                    if (qimExtract(work[pos]) == 1) votes1++
                }
                val tileIdx = ((by % 8) * 8 + (bx % 8)) % FRAME_BITS
                if (votes1 * 2 >= MID_BAND.size) tileVotesOn[tileIdx]++
                else tileVotesOff[tileIdx]++
            }
        }

        // 每个 tile 位置多数表决 -> 64 位帧（8x8 二维布局，frameBits[ty*8+tx]）
        val frameBits = IntArray(FRAME_BITS) { i ->
            if (tileVotesOn[i] >= tileVotesOff[i]) 1 else 0
        }

        // 二维循环移位搜索：穷举 (dy,dx) in 0..7 x 0..7，对 SYNC 做 Hamming 匹配。
        // 裁剪致块栅格起点偏移 -> tile 域 (dy,dx) 二维循环移位，一维搜索无法对齐。
        // SYNC bit j (MSB first, j=0..15) 位于帧 2D 位置 (j/8, j%8)；
        // 位移 (dy,dx) 后应从 frameBits[((j/8+dy)%8)*8 + (j%8+dx)%8] 读取。
        var bestHamming = SYNC_BITS + 1
        var bestDy = -1
        var bestDx = -1
        for (dy in 0 until 8) {
            for (dx in 0 until 8) {
                var h = 0
                for (j in 0 until SYNC_BITS) {
                    val want = (SYNC shr (SYNC_BITS - 1 - j)) and 1
                    val ty = (j / 8 + dy) % 8
                    val tx = (j % 8 + dx) % 8
                    if (frameBits[ty * 8 + tx] != want) h++
                }
                if (h < bestHamming) {
                    bestHamming = h
                    bestDy = dy
                    bestDx = dx
                }
            }
        }
        if (bestDy < 0 || bestHamming > SYNC_TOL) return null

        // PAYLOAD bit i (i=0..47) 位于帧 2D 位置 (2+i/8, i%8)；
        // 位移 (bestDy,bestDx) 后从 frameBits[((2+i/8+bestDy)%8)*8 + (i%8+bestDx)%8] 读取。
        val payload = ByteArray(PAYLOAD_BYTES)
        for (i in 0 until PAYLOAD_BITS) {
            val ty = (2 + i / 8 + bestDy) % 8
            val tx = (i % 8 + bestDx) % 8
            val bit = frameBits[ty * 8 + tx]
            val byteIdx = i / 8
            val bitIdx = i % 8
            if (bit == 1) {
                payload[byteIdx] = (payload[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        return payload
    }

    /** QIM 嵌入：把系数 [c] 量化到对应比特子格上。 */
    private fun qimEmbed(c: Double, bit: Int): Double {
        val nearest0 = round(c / Q) * Q
        val nearest1 = round((c - Q / 2.0) / Q) * Q + Q / 2.0
        return if (bit == 0) nearest0 else nearest1
    }

    /** QIM 提取：根据系数到两个子格的距离判定比特。 */
    private fun qimExtract(c: Double): Int {
        val r = ((c % Q) + Q) % Q
        val d0 = Math.min(r, Q - r)
        val d1 = abs(r - Q / 2.0)
        return if (d0 <= d1) 0 else 1
    }

    /** 构造 64 位帧：SYNC(16, MSB first) ++ PAYLOAD(48, LSB first per byte)。 */
    private fun buildFrame(payload: ByteArray): IntArray {
        val frame = IntArray(FRAME_BITS)
        for (i in 0 until SYNC_BITS) {
            frame[i] = (SYNC shr (SYNC_BITS - 1 - i)) and 1
        }
        for (i in 0 until PAYLOAD_BITS) {
            val byteIdx = i / 8
            val bitIdx = i % 8
            frame[SYNC_BITS + i] = (payload[byteIdx].toInt() shr bitIdx) and 1
        }
        return frame
    }
}
