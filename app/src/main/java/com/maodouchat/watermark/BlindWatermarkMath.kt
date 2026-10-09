package com.maodouchat.watermark

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

// DWT/DCT/SVD 数学工具：从 ReferenceBlindWatermark 拆出的纯函数簇，零行为改动。
internal object BlindWatermarkMath {

    private const val SQRT2 = 1.4142135623730951

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
        val out = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (u in 0 until ReferenceBlindWatermark.BLOCK) {
            for (v in 0 until ReferenceBlindWatermark.BLOCK) {
                var sum = 0.0
                for (r in 0 until ReferenceBlindWatermark.BLOCK) {
                    for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                        sum += block[r][c] *
                            cos(PI * (2 * r + 1) * u / (2.0 * ReferenceBlindWatermark.BLOCK)) *
                            cos(PI * (2 * c + 1) * v / (2.0 * ReferenceBlindWatermark.BLOCK))
                    }
                }
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cvv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                out[u][v] = (sum * cu * cvv * 2.0 / ReferenceBlindWatermark.BLOCK).toFloat()
            }
        }
        return out
    }

    /** 4×4 正交 IDCT（[dct2] 的逆）。 */
    internal fun idct2(block: Array<FloatArray>): Array<FloatArray> {
        val out = Array(ReferenceBlindWatermark.BLOCK) { FloatArray(ReferenceBlindWatermark.BLOCK) }
        for (r in 0 until ReferenceBlindWatermark.BLOCK) {
            for (c in 0 until ReferenceBlindWatermark.BLOCK) {
                var sum = 0.0
                for (u in 0 until ReferenceBlindWatermark.BLOCK) {
                    for (v in 0 until ReferenceBlindWatermark.BLOCK) {
                        val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                        val cvv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                        sum += block[u][v] * cu * cvv *
                            cos(PI * (2 * r + 1) * u / (2.0 * ReferenceBlindWatermark.BLOCK)) *
                            cos(PI * (2 * c + 1) * v / (2.0 * ReferenceBlindWatermark.BLOCK))
                    }
                }
                out[r][c] = (sum * 2.0 / ReferenceBlindWatermark.BLOCK).toFloat()
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
