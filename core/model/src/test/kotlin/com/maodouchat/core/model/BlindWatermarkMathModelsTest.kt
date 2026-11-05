package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlindWatermarkMathModelsTest {

    @Test
    fun `quad keeps its four subbands`() {
        val quad = Quad(
            arrayOf(floatArrayOf(1f, 2f)),
            arrayOf(floatArrayOf(3f)),
            arrayOf(floatArrayOf(4f)),
            arrayOf(floatArrayOf(5f))
        )
        assertEquals(2f, quad.ca[0][1], 0.001f)
        assertEquals(3f, quad.ch[0][0], 0.001f)
        assertEquals(4f, quad.cv[0][0], 0.001f)
        assertEquals(5f, quad.cd[0][0], 0.001f)
    }
}
