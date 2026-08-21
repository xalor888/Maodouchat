package com.maodouchat.watermark

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 9.4xx：blind_watermark（github.com/guofei9987/blind_watermark）核心算法的 Kotlin 忠实移植。
 *
 * 与原版 `bwm_core.py` / `blind_watermark.py` 一一对应：
 * - read_img_arr：BGR→YUV → 补白边至偶数 → 每通道 2D Haar DWT，取 LL（ca）子带
 * - 4×4 分块（仅整除部分），每块：2D DCT → 按 password_img 种子置换打乱 →
 *   SVD → 对 s[0]/s[1] 量化嵌入（d1=36 / d2=20）→ 重建 → 逆置换 → IDCT
 * - 水印位流先用 password_wm 种子洗牌（Fisher-Yates，等价 numpy RandomState.shuffle）
 * - 提取：每块 DCT→置换→SVD，由 s[0]%d1、s[1]%d2 判位，3 通道 × 循环重复求平均，
 *   一维 kmeans 二值化，逆洗牌还原位序，拼回字节
 *
 * 与原版差异（仅影响数值、不影响自洽往返）：DCT 用正交基；SVD 用单边 Jacobi；
 * RNG 用 java.util.Random（同一 seed 两侧一致即可）。
 */
object ReferenceBlindWatermark {
    const val BLOCK = 4
    const val D1 = 36.0
    const val D2 = 20.0

    private const val SQRT2 = 1.4142135623730951

    // ── 公开 API ──────────────────────────────────────────────

    /** 在 ARGB 像素上嵌入 [payload]（8bit/字节展开位流），返回新像素数组；原数组不变。 */
    fun embedPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        payload: ByteArray,
        passwordWm: Long = 1L,
        passwordImg: Long = 1L
    ): IntArray {
        require(pixels.size == width * height) { "pixels size mismatch" }
        if (payload.isEmpty()) return pixels.copyOf()
        val img = ArgbImage(pixels, width, height)
        val core = WatermarkCore(passwordImg)
        core.readImage(img)
        val wmBits = bytesToBits(payload)
        if (core.blockNum <= wmBits.size) return pixels.copyOf() // 图太小嵌不下：原样返回
        val shuffled = shuffleBits(wmBits, passwordWm)
        core.embedCore(shuffled)
        return img.toPixels()
    }

    /** 提取 [payloadBitCount] 位载荷；无水印/图像过小返回 null。 */
    fun extractPayload(
        pixels: IntArray,
        width: Int,
        height: Int,
        payloadBitCount: Int,
        passwordWm: Long = 1L,
        passwordImg: Long = 1L
    ): ByteArray? {
        require(pixels.size == width * height) { "pixels size mismatch" }
        if (payloadBitCount <= 0 || payloadBitCount % 8 != 0) return null
        val img = ArgbImage(pixels, width, height)
        val core = WatermarkCore(passwordImg)
        core.readImage(img)
        if (core.blockNum <= payloadBitCount) return null
        val raw = core.extractRaw()                    // 3×blockNum 软比特
        val wmAvg = DoubleArray(payloadBitCount)
        for (i in 0 until payloadBitCount) {
            var sum = 0.0
            var n = 0
            for (ch in 0 until 3) {
                var k = i
                while (k < core.blockNum) {
                    sum += raw[ch][k]
                    n++
                    k += payloadBitCount
                }
            }
            wmAvg[i] = if (n == 0) 0.0 else sum / n
        }
        val (bits, centers) = oneDimKmeans(wmAvg)
        // 9.4xx：置信度门——真实水印的软比特在平均后紧密聚在 0/1 两侧（类中心分离大）；
        // 无水印图片的软比特近似均匀噪声，kmeans 硬分后类中心分离小。
        // 分离度过低判定无水印返回 null，避免对干净图片提取出全 1/全 0 假阳性。
        if (centers.size < 2 || centers[1] - centers[0] < 0.6) return null
        val unshuffled = BooleanArray(payloadBitCount)
        val idx = indexShuffle(payloadBitCount, passwordWm)
        for (k in 0 until payloadBitCount) {
            unshuffled[idx[k]] = bits[k]
        }
        return bitsToBytes(unshuffled)
    }

    // ── 位流 ↔ 字节 ──────────────────────────────────────────

    internal fun bytesToBits(bytes: ByteArray): BooleanArray {
        val bits = BooleanArray(bytes.size * 8)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            for (b in 0 until 8) bits[i * 8 + b] = ((v shr (7 - b)) and 1) == 1
        }
        return bits
    }

    internal fun bitsToBytes(bits: BooleanArray): ByteArray {
        require(bits.size % 8 == 0) { "bit length must be multiple of 8" }
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var v = 0
            for (b in 0 until 8) if (bits[i * 8 + b]) v = v or (1 shl (7 - b))
            bytes[i] = v.toByte()
        }
        return bytes
    }

    /** numpy RandomState(seed).shuffle 的等价：返回洗牌后的索引排列（Fisher-Yates）。 */
    private fun indexShuffle(n: Int, seed: Long): IntArray {
        val idx = IntArray(n) { it }
        val rng = java.util.Random(seed)
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
        }
        return idx
    }

    private fun shuffleBits(bits: BooleanArray, seed: Long): BooleanArray {
        val idx = indexShuffle(bits.size, seed)
        return BooleanArray(bits.size) { bits[idx[it]] }
    }

    /** bwm_core.one_dim_kmeans：1 维 2 类 kmeans 阈值二值化；返回 (类别, 两簇中心)。 */
    private fun oneDimKmeans(inputs: DoubleArray): Pair<BooleanArray, DoubleArray> {
        if (inputs.isEmpty()) return BooleanArray(0) to doubleArrayOf()
        val lo = inputs.minOrNull() ?: 0.0
        val hi = inputs.maxOrNull() ?: 1.0
        if (hi - lo < 1e-9) {
            return BooleanArray(inputs.size) { inputs[it] >= 0.5 } to doubleArrayOf(lo, hi)
        }
        var threshold = 0.0
        var center0 = lo
        var center1 = hi
        repeat(300) {
            threshold = (center0 + center1) / 2
            var s0 = 0.0; var n0 = 0
            var s1 = 0.0; var n1 = 0
            for (v in inputs) {
                if (v > threshold) { s1 += v; n1++ } else { s0 += v; n0++ }
            }
            val newC0 = if (n0 > 0) s0 / n0 else center0
            val newC1 = if (n1 > 0) s1 / n1 else center1
            center0 = newC0; center1 = newC1
            val newMid = (newC0 + newC1) / 2
            if (abs(newMid - threshold) < 1e-6) {
                threshold = newMid
                return BooleanArray(inputs.size) { inputs[it] > threshold } to doubleArrayOf(center0, center1)
            }
        }
        return BooleanArray(inputs.size) { inputs[it] > threshold } to doubleArrayOf(center0, center1)
    }

    // ── 像素容器（BGR float 平面，ARGB 兼容）───────────────────

    private class ArgbImage(pixels: IntArray, val width: Int, val height: Int) {
        /** 每通道平面：B=0, G=1, R=2（原版 BGR 顺序）。 */
        val bgr = Array(3) { FloatArray(width * height) }
        var alpha: FloatArray? = null

        init {
            var hasAlpha = false
            val a = FloatArray(width * height)
            for (i in pixels.indices) {
                val p = pixels[i]
                bgr[0][i] = (p and 0xFF).toFloat()
                bgr[1][i] = ((p shr 8) and 0xFF).toFloat()
                bgr[2][i] = ((p shr 16) and 0xFF).toFloat()
                val av = (p shr 24) and 0xFF
                a[i] = av.toFloat()
                if (av < 255) hasAlpha = true
            }
            if (hasAlpha) alpha = a
        }

        /** 可嵌入的最大位容量（块数）。 */
        fun maxBlocks(): Int {
            val caH = (height + 1) / 2
            val caW = (width + 1) / 2
            return (caH / BLOCK) * (caW / BLOCK)
        }

        fun toPixels(): IntArray {
            val out = IntArray(width * height)
            for (i in out.indices) {
                val r = round(bgr[2][i]).coerceIn(0f, 255f).toInt()
                val g = round(bgr[1][i]).coerceIn(0f, 255f).toInt()
                val b = round(bgr[0][i]).coerceIn(0f, 255f).toInt()
                val a = alpha?.get(i)?.let { round(it).coerceIn(0f, 255f).toInt() } ?: 0xFF
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        }
    }

    private class WatermarkCore(private val passwordImg: Long) {
        lateinit var img: ArgbImage
        var imgH = 0
        var imgW = 0
        var caH = 0
        var caW = 0
        var blockNum = 0

        var ca = arrayOf<Array<FloatArray>>()
        var ch = arrayOf<Array<FloatArray>>()
        var cv = arrayOf<Array<FloatArray>>()
        var cd = arrayOf<Array<FloatArray>>()
        var idxShuffle = emptyArray<IntArray>()

        fun readImage(image: ArgbImage) {
            img = image
            imgH = image.height
            imgW = image.width
            val padH = imgH + imgH % 2
            val padW = imgW + imgW % 2

            // BGR → YUV（OpenCV 公式），补边区域默认 0
            val yuvPlanes = Array(3) { Array(padH) { FloatArray(padW) } }
            for (row in 0 until imgH) {
                for (col in 0 until imgW) {
                    val i = row * imgW + col
                    val b = image.bgr[0][i]
                    val g = image.bgr[1][i]
                    val r = image.bgr[2][i]
                    yuvPlanes[0][row][col] = 0.299f * r + 0.587f * g + 0.114f * b
                    yuvPlanes[1][row][col] = -0.14713f * r - 0.28886f * g + 0.436f * b + 128f
                    yuvPlanes[2][row][col] = 0.615f * r - 0.51499f * g - 0.10001f * b + 128f
                }
            }
            ca = Array(3) { emptyArray() }
            ch = Array(3) { emptyArray() }
            cv = Array(3) { emptyArray() }
            cd = Array(3) { emptyArray() }
            for (chIdx in 0 until 3) {
                val q = dwt2(yuvPlanes[chIdx])
                ca[chIdx] = q.ca
                ch[chIdx] = q.ch
                cv[chIdx] = q.cv
                cd[chIdx] = q.cd
            }
            caH = padH / 2
            caW = padW / 2
            blockNum = (caH / BLOCK) * (caW / BLOCK)

            // random_strategy1 等价：一次性按块顺序生成 16 个随机数的 argsort 置换
            val rng = java.util.Random(passwordImg)
            idxShuffle = Array(blockNum) {
                val values = Array(16) { rng.nextDouble() }
                values.indices.sortedBy { values[it] }.toIntArray()
            }
        }

        /** embed()：逐通道改块 → idwt → YUV→BGR 写回。 */
        fun embedCore(shuffledWm: BooleanArray) {
            val wmSize = shuffledWm.size
            val padH = imgH + imgH % 2
            val padW = imgW + imgW % 2
            val resultYuv = Array(3) { arrayOf<FloatArray>() }
            for (chIdx in 0 until 3) {
                val cA = ca[chIdx].map { it.copyOf() }.toTypedArray()
                val blocksX = caW / BLOCK
                for (i in 0 until blockNum) {
                    val by = i / blocksX
                    val bx = i % blocksX
                    val block = Array(BLOCK) { FloatArray(BLOCK) }
                    for (r in 0 until BLOCK) {
                        for (c in 0 until BLOCK) {
                            block[r][c] = cA[by * BLOCK + r][bx * BLOCK + c]
                        }
                    }
                    val wmBit = if (shuffledWm[i % wmSize]) 1.0 else 0.0
                    val out = blockAddWm(block, idxShuffle[i], wmBit)
                    for (r in 0 until BLOCK) {
                        for (c in 0 until BLOCK) {
                            cA[by * BLOCK + r][bx * BLOCK + c] = out[r][c]
                        }
                    }
                }
                resultYuv[chIdx] = idwt2(cA, ch[chIdx], cv[chIdx], cd[chIdx])
            }
            // YUV → BGR 写回（裁剪补边）
            val y = resultYuv[0]; val u = resultYuv[1]; val v = resultYuv[2]
            for (row in 0 until imgH) {
                for (col in 0 until imgW) {
                    val i = row * imgW + col
                    val yy = y[row][col].coerceIn(0f, 255f)
                    val uu = u[row][col].coerceIn(0f, 255f)
                    val vv = v[row][col].coerceIn(0f, 255f)
                    val r = yy + 1.13983f * (vv - 128f)
                    val g = yy - 0.39465f * (uu - 128f) - 0.58060f * (vv - 128f)
                    val b = yy + 2.03211f * (uu - 128f)
                    img.bgr[2][i] = r.coerceIn(0f, 255f)
                    img.bgr[1][i] = g.coerceIn(0f, 255f)
                    img.bgr[0][i] = b.coerceIn(0f, 255f)
                }
            }
        }

        /** extract_raw：每块返回软比特 (3*wm1+wm2)/4。 */
        fun extractRaw(): Array<DoubleArray> {
            val result = Array(3) { DoubleArray(blockNum) }
            val blocksX = caW / BLOCK
            for (chIdx in 0 until 3) {
                val cA = ca[chIdx]
                for (i in 0 until blockNum) {
                    val by = i / blocksX
                    val bx = i % blocksX
                    val block = Array(BLOCK) { FloatArray(BLOCK) }
                    for (r in 0 until BLOCK) {
                        for (c in 0 until BLOCK) {
                            block[r][c] = cA[by * BLOCK + r][bx * BLOCK + c]
                        }
                    }
                    result[chIdx][i] = blockGetWm(block, idxShuffle[i])
                }
            }
            return result
        }

        // ── 变换与嵌入/提取原语 ─────────────────────────────

        private fun blockAddWm(block: Array<FloatArray>, perm: IntArray, wmBit: Double): Array<FloatArray> {
            val dctBlock = dct2(block)
            val shuffled = Array(BLOCK) { FloatArray(BLOCK) }
            for (k in 0 until 16) {
                shuffled[k / BLOCK][k % BLOCK] = dctBlock[perm[k] / BLOCK][perm[k] % BLOCK]
            }
            val (u, s, vt) = svd(shuffled)
            s[0] = (floor(s[0] / D1) + 0.25 + 0.5 * wmBit) * D1
            s[1] = (floor(s[1] / D2) + 0.25 + 0.5 * wmBit) * D2
            val rebuilt = Array(BLOCK) { FloatArray(BLOCK) }
            for (r in 0 until BLOCK) {
                for (c in 0 until BLOCK) {
                    var sum = 0.0
                    for (k in 0 until BLOCK) {
                        sum += u[r][k] * s[k] * vt[k][c]
                    }
                    rebuilt[r][c] = sum.toFloat()
                }
            }
            // 逆置换：orig[perm[k]] = rebuilt[k]
            val unshuffled = Array(BLOCK) { FloatArray(BLOCK) }
            for (k in 0 until 16) {
                unshuffled[perm[k] / BLOCK][perm[k] % BLOCK] = rebuilt[k / BLOCK][k % BLOCK]
            }
            return idct2(unshuffled)
        }

        private fun blockGetWm(block: Array<FloatArray>, perm: IntArray): Double {
            val dctBlock = dct2(block)
            val shuffled = Array(BLOCK) { FloatArray(BLOCK) }
            for (k in 0 until 16) {
                shuffled[k / BLOCK][k % BLOCK] = dctBlock[perm[k] / BLOCK][perm[k] % BLOCK]
            }
            val (_, s, _) = svd(shuffled)
            val wm1 = if (positiveMod(s[0], D1) > D1 / 2) 1.0 else 0.0
            val wm2 = if (positiveMod(s[1], D2) > D2 / 2) 1.0 else 0.0
            return (wm1 * 3 + wm2) / 4
        }

        private fun positiveMod(x: Double, m: Double): Double {
            val r = x % m
            return if (r < 0) r + m else r
        }
    }

    // ── 数学工具：DWT / DCT / SVD ────────────────────────────

    internal class Quad(
        val ca: Array<FloatArray>,
        val ch: Array<FloatArray>,
        val cv: Array<FloatArray>,
        val cd: Array<FloatArray>
    )

    /** 2D Haar DWT（1/√2 归一化；输入尺寸必须为偶数）。 */
    internal fun dwt2(src: Array<FloatArray>): Quad {
        val h = src.size
        val w = src[0].size
        val rows = Array(h) { FloatArray(w) }
        for (r in 0 until h) {
            for (c in 0 until w / 2) {
                val a = src[r][2 * c]
                val b = src[r][2 * c + 1]
                rows[r][c] = ((a + b) / SQRT2).toFloat()
                rows[r][w / 2 + c] = ((a - b) / SQRT2).toFloat()
            }
        }
        val halfH = h / 2
        val halfW = w / 2
        val ca = Array(halfH) { FloatArray(halfW) }
        val chh = Array(halfH) { FloatArray(halfW) }
        val cvv = Array(halfH) { FloatArray(halfW) }
        val cdd = Array(halfH) { FloatArray(halfW) }
        for (r in 0 until halfH) {
            for (c in 0 until halfW) {
                val a0 = rows[2 * r][c]
                val a1 = rows[2 * r + 1][c]
                ca[r][c] = ((a0 + a1) / SQRT2).toFloat()
                chh[r][c] = ((a0 - a1) / SQRT2).toFloat()
                val b0 = rows[2 * r][halfW + c]
                val b1 = rows[2 * r + 1][halfW + c]
                cvv[r][c] = ((b0 + b1) / SQRT2).toFloat()
                cdd[r][c] = ((b0 - b1) / SQRT2).toFloat()
            }
        }
        return Quad(ca, chh, cvv, cdd)
    }

    /** 2D Haar IDWT（[dwt2] 的逆）。 */
    internal fun idwt2(ca: Array<FloatArray>, chh: Array<FloatArray>, cvv: Array<FloatArray>, cdd: Array<FloatArray>): Array<FloatArray> {
        val halfH = ca.size
        val halfW = ca[0].size
        val h = halfH * 2
        val w = halfW * 2
        val cols = Array(h) { FloatArray(w) }
        for (r in 0 until halfH) {
            for (c in 0 until halfW) {
                val l = ca[r][c]
                val hh = chh[r][c]
                cols[2 * r][c] = ((l + hh) / SQRT2).toFloat()
                cols[2 * r + 1][c] = ((l - hh) / SQRT2).toFloat()
                val b0 = cvv[r][c]
                val b1 = cdd[r][c]
                cols[2 * r][halfW + c] = ((b0 + b1) / SQRT2).toFloat()
                cols[2 * r + 1][halfW + c] = ((b0 - b1) / SQRT2).toFloat()
            }
        }
        val out = Array(h) { FloatArray(w) }
        for (r in 0 until h) {
            for (c in 0 until halfW) {
                val l = cols[r][c]
                val hh = cols[r][halfW + c]
                out[r][2 * c] = ((l + hh) / SQRT2).toFloat()
                out[r][2 * c + 1] = ((l - hh) / SQRT2).toFloat()
            }
        }
        return out
    }

    /** 4×4 正交 DCT-II（与 [idct2] 配对）。 */
    internal fun dct2(block: Array<FloatArray>): Array<FloatArray> {
        val out = Array(BLOCK) { FloatArray(BLOCK) }
        for (u in 0 until BLOCK) {
            for (v in 0 until BLOCK) {
                var sum = 0.0
                for (r in 0 until BLOCK) {
                    for (c in 0 until BLOCK) {
                        sum += block[r][c] *
                            cos(PI * (2 * r + 1) * u / (2.0 * BLOCK)) *
                            cos(PI * (2 * c + 1) * v / (2.0 * BLOCK))
                    }
                }
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cvv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                out[u][v] = (sum * cu * cvv * 2.0 / BLOCK).toFloat()
            }
        }
        return out
    }

    /** 4×4 正交 IDCT（[dct2] 的逆）。 */
    internal fun idct2(block: Array<FloatArray>): Array<FloatArray> {
        val out = Array(BLOCK) { FloatArray(BLOCK) }
        for (r in 0 until BLOCK) {
            for (c in 0 until BLOCK) {
                var sum = 0.0
                for (u in 0 until BLOCK) {
                    for (v in 0 until BLOCK) {
                        val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                        val cvv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                        sum += block[u][v] * cu * cvv *
                            cos(PI * (2 * r + 1) * u / (2.0 * BLOCK)) *
                            cos(PI * (2 * c + 1) * v / (2.0 * BLOCK))
                    }
                }
                out[r][c] = (sum * 2.0 / BLOCK).toFloat()
            }
        }
        return out
    }

    /**
     * 4×4 单边 Jacobi SVD：返回 (u, s, vt)，A ≈ u·diag(s)·vt，s 降序
     * （与原版 numpy 行为一致：量化作用于最大/次大奇异值）。
     */
    internal fun svd(a: Array<FloatArray>): Triple<Array<FloatArray>, DoubleArray, Array<FloatArray>> {
        val n = a.size
        val b = Array(n) { DoubleArray(n) }
        for (r in 0 until n) for (c in 0 until n) b[r][c] = a[r][c].toDouble()
        val v = Array(n) { DoubleArray(n) { i -> if (it == i) 1.0 else 0.0 } }
        var done = false
        repeat(60) {
            if (done) return@repeat
            var rotated = false
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    var alpha = 0.0; var beta = 0.0; var gamma = 0.0
                    for (i in 0 until n) {
                        alpha += b[i][p] * b[i][p]
                        beta += b[i][q] * b[i][q]
                        gamma += b[i][p] * b[i][q]
                    }
                    if (abs(gamma) < 1e-12) continue
                    val zeta = (beta - alpha) / (2 * gamma)
                    val t = if (zeta >= 0) 1.0 / (zeta + sqrt(1 + zeta * zeta))
                    else -1.0 / (-zeta + sqrt(1 + zeta * zeta))
                    val c = 1.0 / sqrt(1 + t * t)
                    val s = c * t
                    for (i in 0 until n) {
                        val bp = b[i][p]; val bq = b[i][q]
                        b[i][p] = c * bp - s * bq
                        b[i][q] = s * bp + c * bq
                    }
                    for (i in 0 until n) {
                        val vp = v[i][p]; val vq = v[i][q]
                        v[i][p] = c * vp - s * vq
                        v[i][q] = s * vp + c * vq
                    }
                    rotated = true
                }
            }
            if (!rotated) done = true
        }
        val s = DoubleArray(n)
        val u = Array(n) { DoubleArray(n) }
        for (j in 0 until n) {
            var norm = 0.0
            for (i in 0 until n) norm += b[i][j] * b[i][j]
            s[j] = sqrt(norm)
            if (s[j] > 1e-12) {
                for (i in 0 until n) u[i][j] = b[i][j] / s[j]
            } else {
                u[j][j] = 1.0
            }
        }
        // 降序排列（U/V 列同步交换）
        val order = (0 until n).sortedByDescending { s[it] }
        val su = Array(n) { DoubleArray(n) }
        val ss = DoubleArray(n)
        val sv = Array(n) { DoubleArray(n) }
        for (newIdx in order.indices) {
            val old = order[newIdx]
            ss[newIdx] = s[old]
            for (i in 0 until n) {
                su[i][newIdx] = u[i][old]
                sv[i][newIdx] = v[i][old]
            }
        }
        val uF = Array(n) { FloatArray(n) { c -> su[it][c].toFloat() } }
        val vtF = Array(n) { FloatArray(n) { c -> sv[c][it].toFloat() } }
        return Triple(uF, ss, vtF)
    }
}
