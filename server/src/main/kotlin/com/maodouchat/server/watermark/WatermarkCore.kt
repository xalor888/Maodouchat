package com.maodouchat.server.watermark

import kotlin.math.floor

// 原 ReferenceBlindWatermark 成员按域拆出的扩展函数（同包调用，零行为改动）。

internal class WatermarkCore(private val passwordImg: Long) {
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
            val q = ReferenceBlindWatermark.dwt2(yuvPlanes[chIdx])
            ca[chIdx] = q.ca
            ch[chIdx] = q.ch
            cv[chIdx] = q.cv
            cd[chIdx] = q.cd
        }
        caH = padH / 2
        caW = padW / 2
        blockNum = (caH / ReferenceBlindWatermark.BLOCK) * (caW / ReferenceBlindWatermark.BLOCK)

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
            val blocksX = caW / ReferenceBlindWatermark.BLOCK
            for (i in 0 until blockNum) {
                val by = i / blocksX
                val bx = i % blocksX
                val block = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
                for (r in 0 until ReferenceBlindWatermark.BLOCK) {
                    for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                        block[r][c] = cA[by * ReferenceBlindWatermark.BLOCK + r][bx * ReferenceBlindWatermark.BLOCK + c]
                    }
                }
                val wmBit = if (shuffledWm[i % wmSize]) 1.0 else 0.0
                val out = blockAddWm(block, idxShuffle[i], wmBit)
                for (r in 0 until ReferenceBlindWatermark.BLOCK) {
                    for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                        cA[by * ReferenceBlindWatermark.BLOCK + r][bx * ReferenceBlindWatermark.BLOCK + c] = out[r][c]
                    }
                }
            }
            resultYuv[chIdx] = ReferenceBlindWatermark.idwt2(cA, ch[chIdx], cv[chIdx], cd[chIdx])
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
        val blocksX = caW / ReferenceBlindWatermark.BLOCK
        for (chIdx in 0 until 3) {
            val cA = ca[chIdx]
            for (i in 0 until blockNum) {
                val by = i / blocksX
                val bx = i % blocksX
                val block = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
                for (r in 0 until ReferenceBlindWatermark.BLOCK) {
                    for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                        block[r][c] = cA[by * ReferenceBlindWatermark.BLOCK + r][bx * ReferenceBlindWatermark.BLOCK + c]
                    }
                }
                result[chIdx][i] = blockGetWm(block, idxShuffle[i])
            }
        }
        return result
    }

    // ── 变换与嵌入/提取原语 ─────────────────────────────

    private fun blockAddWm(block: Array<FloatArray>, perm: IntArray, wmBit: Double): Array<FloatArray> {
        val dctBlock = ReferenceBlindWatermark.dct2(block)
        val shuffled = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (k in 0 until 16) {
            shuffled[k / ReferenceBlindWatermark.BLOCK][k % ReferenceBlindWatermark.BLOCK] = dctBlock[perm[k] / ReferenceBlindWatermark.BLOCK][perm[k] % ReferenceBlindWatermark.BLOCK]
        }
        val (u, s, vt) = ReferenceBlindWatermark.svd(shuffled)
        s[0] = (floor(s[0] / ReferenceBlindWatermark.D1) + 0.25 + 0.5 * wmBit) * ReferenceBlindWatermark.D1
        s[1] = (floor(s[1] / ReferenceBlindWatermark.D2) + 0.25 + 0.5 * wmBit) * ReferenceBlindWatermark.D2
        val rebuilt = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (r in 0 until ReferenceBlindWatermark.BLOCK) {
            for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                var sum = 0.0
                for (k in 0 until ReferenceBlindWatermark.BLOCK) {
                    sum += u[r][k] * s[k] * vt[k][c]
                }
                rebuilt[r][c] = sum.toFloat()
            }
        }
        // 逆置换：orig[perm[k]] = rebuilt[k]
        val unshuffled = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (k in 0 until 16) {
            unshuffled[perm[k] / ReferenceBlindWatermark.BLOCK][perm[k] % ReferenceBlindWatermark.BLOCK] = rebuilt[k / ReferenceBlindWatermark.BLOCK][k % ReferenceBlindWatermark.BLOCK]
        }
        return ReferenceBlindWatermark.idct2(unshuffled)
    }

    private fun blockGetWm(block: Array<FloatArray>, perm: IntArray): Double {
        val dctBlock = ReferenceBlindWatermark.dct2(block)
        val shuffled = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (k in 0 until 16) {
            shuffled[k / ReferenceBlindWatermark.BLOCK][k % ReferenceBlindWatermark.BLOCK] = dctBlock[perm[k] / ReferenceBlindWatermark.BLOCK][perm[k] % ReferenceBlindWatermark.BLOCK]
        }
        val (_, s, _) = ReferenceBlindWatermark.svd(shuffled)
        val wm1 = if (positiveMod(s[0], ReferenceBlindWatermark.D1) > ReferenceBlindWatermark.D1 / 2) 1.0 else 0.0
        val wm2 = if (positiveMod(s[1], ReferenceBlindWatermark.D2) > ReferenceBlindWatermark.D2 / 2) 1.0 else 0.0
        return (wm1 * 3 + wm2) / 4
    }

    private fun positiveMod(x: Double, m: Double): Double {
        val r = x % m
        return if (r < 0) r + m else r
    }
}
