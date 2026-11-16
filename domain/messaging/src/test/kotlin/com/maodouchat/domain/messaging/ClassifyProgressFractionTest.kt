package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyProgressFractionTest {

    @Test
    fun halfOfMax() {
        assertEquals(0.5f, classifyProgressFraction(5, 10), 0.0001f)
    }

    @Test
    fun maxIsOne() {
        assertEquals(1f, classifyProgressFraction(10, 10), 0.0001f)
    }

    @Test
    fun overMaxClampsToOne() {
        assertEquals(1f, classifyProgressFraction(12, 10), 0.0001f)
    }

    @Test
    fun zeroMaxNeverDividesByZero() {
        assertEquals(0f, classifyProgressFraction(0, 0), 0.0001f)
        assertEquals(1f, classifyProgressFraction(3, 0), 0.0001f)
    }
}
