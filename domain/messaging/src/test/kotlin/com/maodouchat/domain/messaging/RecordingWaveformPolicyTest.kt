package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingWaveformPolicyTest {

    @Test
    fun `empty waveform yields 32 bars at 0_08`() {
        val bars = RecordingWaveformPolicy.resample(emptyList(), 0f)
        assertEquals(32, bars.size)
        assertTrue(bars.all { it == 0.08f })
    }

    @Test
    fun `single sample resamples to its value except boosted tail`() {
        val bars = RecordingWaveformPolicy.resample(listOf(0.5f), 0f)
        assertEquals(32, bars.size)
        assertTrue(bars.dropLast(3).all { it == 0.5f })
    }

    @Test
    fun `values are clamped to 0 to 1`() {
        val bars = RecordingWaveformPolicy.resample(listOf(-1f, 2f), 0f)
        assertTrue(bars.all { it in 0f..1f })
    }

    @Test
    fun `last three bars follow live amplitude`() {
        val bars = RecordingWaveformPolicy.resample(listOf(0.1f), 1f)
        assertEquals(0.7f, bars[29])
        assertEquals(0.8f, bars[30])
        assertEquals(0.9f, bars[31])
    }

    @Test
    fun `bar height fraction spans 0_12 to 1`() {
        assertEquals(0.12f, RecordingWaveformPolicy.barHeightFraction(0f))
        assertEquals(1f, RecordingWaveformPolicy.barHeightFraction(1f))
        assertEquals(0.12f, RecordingWaveformPolicy.barHeightFraction(-1f))
        assertEquals(1f, RecordingWaveformPolicy.barHeightFraction(2f))
    }

    @Test
    fun `mid height maps linearly`() {
        assertEquals(0.56f, RecordingWaveformPolicy.barHeightFraction(0.5f))
    }
}
