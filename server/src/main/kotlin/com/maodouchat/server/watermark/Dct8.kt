package com.maodouchat.server.watermark

import kotlin.math.cos
import kotlin.math.PI

/**
 * 纯 Kotlin 8x8 二维 DCT-II / IDCT-II 实现（JPEG 同款块尺寸）。
 *
 * 使用正交归一化基矩阵 M（M^T = M^{-1}），因此：
 *   正变换 F = M · X · Mᵀ
 *   反变换 X = Mᵀ · F · M
 *
 * 不依赖任何 Android / 第三方库，可在 JVM 单元测试中直接验证往返精度。
 * 这是频域盲水印的数学底座：把图像分块做 DCT，在中频系数上做 QIM 嵌入，
 * 再 IDCT 还原——对 JPEG 重编码、缩放、裁剪有较好鲁棒性，且人眼不可见。
 */
object Dct8 {
    const val N = 8
    const val BLOCK = N * N

    /** 正交归一化 DCT-II 基矩阵，M[k][n] = α(k)·cos(π(2n+1)k/(2N))。 */
    private val M: DoubleArray = run {
        val m = DoubleArray(N * N)
        for (k in 0 until N) {
            val alpha = if (k == 0) 1.0 / Math.sqrt(N.toDouble()) else Math.sqrt(2.0 / N)
            for (n in 0 until N) {
                m[k * N + n] = alpha * cos(PI * (2 * n + 1) * k / (2.0 * N))
            }
        }
        m
    }

    /** 行内 1D DCT：y = M · x（x 长度 8，原地写入 out）。 */
    private fun dct1d(x: DoubleArray, xOff: Int, out: DoubleArray, outOff: Int) {
        for (k in 0 until N) {
            var s = 0.0
            val row = k * N
            for (n in 0 until N) {
                s += M[row + n] * x[xOff + n]
            }
            out[outOff + k] = s
        }
    }

    /** 行内 1D IDCT：x = Mᵀ · y。 */
    private fun idct1d(y: DoubleArray, yOff: Int, out: DoubleArray, outOff: Int) {
        for (n in 0 until N) {
            var s = 0.0
            for (k in 0 until N) {
                s += M[k * N + n] * y[yOff + k]
            }
            out[outOff + n] = s
        }
    }

    private val tmpRows = DoubleArray(BLOCK)
    private val tmpCols = DoubleArray(BLOCK)
    private val buf1 = DoubleArray(BLOCK)
    private val buf2 = DoubleArray(BLOCK)

    /**
     * 对一个 8x8 块（64 个 double，行优先）做正向 2D DCT-II，结果写回 [out]。
     * 允许 in-place（block 与 out 为同一数组）。
     */
    @Synchronized
    fun forward(block: DoubleArray, out: DoubleArray = block) {
        // 先对每行做 1D DCT -> tmpRows
        for (r in 0 until N) {
            dct1d(block, r * N, tmpRows, r * N)
        }
        // 再对每列做 1D DCT：把列抽到 buf1，变换后写回 out
        for (c in 0 until N) {
            for (r in 0 until N) tmpCols[r] = tmpRows[r * N + c]
            dct1d(tmpCols, 0, buf1, 0)
            for (r in 0 until N) out[r * N + c] = buf1[r]
        }
    }

    /**
     * 对一个 8x8 块做反向 2D DCT-II（IDCT），结果写回 [out]。
     */
    @Synchronized
    fun inverse(block: DoubleArray, out: DoubleArray = block) {
        // 行方向 IDCT
        for (r in 0 until N) {
            idct1d(block, r * N, tmpRows, r * N)
        }
        // 列方向 IDCT
        for (c in 0 until N) {
            for (r in 0 until N) tmpCols[r] = tmpRows[r * N + c]
            idct1d(tmpCols, 0, buf1, 0)
            for (r in 0 until N) out[r * N + c] = buf1[r]
        }
    }
}
