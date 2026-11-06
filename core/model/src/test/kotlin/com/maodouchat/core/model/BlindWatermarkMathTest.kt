package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlindWatermarkMathTest {

    @Test
    fun `dwt2 and idwt2 round-trip`() {
        val src = Array(4) { r -> FloatArray(4) { c -> (r * 4 + c).toFloat() } }
        val q = BlindWatermarkMath.dwt2(src)
        val back = BlindWatermarkMath.idwt2(q.ca, q.ch, q.cv, q.cd)
        assertEquals(4, back.size)
        for (r in 0 until 4) for (c in 0 until 4) {
            assertEquals(src[r][c], back[r][c], 1e-3f)
        }
    }

    @Test
    fun `dct2 and idct2 round-trip`() {
        val block = Array(4) { r -> FloatArray(4) { c -> (r * 4 + c + 1).toFloat() } }
        val back = BlindWatermarkMath.idct2(BlindWatermarkMath.dct2(block))
        for (r in 0 until 4) for (c in 0 until 4) {
            assertEquals(block[r][c], back[r][c], 1e-3f)
        }
    }

    @Test
    fun `svd returns descending singular values`() {
        val a = Array(4) { r -> FloatArray(4) { c -> if (r == c) (4 - r).toFloat() else 0f } }
        val (_, s, _) = BlindWatermarkMath.svd(a)
        assertEquals(4.0, s[0], 1e-6)
        assertEquals(3.0, s[1], 1e-6)
        assertEquals(2.0, s[2], 1e-6)
        assertEquals(1.0, s[3], 1e-6)
    }
}
