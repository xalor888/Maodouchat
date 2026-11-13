package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 录音振幅归一化：随 VoiceCaptureAmplitude 由 app 侧 VoiceCapturePolicyTest 迁入。
class VoiceCaptureAmplitudeTest {

    @Test
    fun normalizeAmplitude_zeroAndPositive() {
        assertEquals(0f, VoiceCaptureAmplitude.normalizeAmplitude(0), 0.0001f)
        assertEquals(0f, VoiceCaptureAmplitude.normalizeAmplitude(-3), 0.0001f)
        val mid = VoiceCaptureAmplitude.normalizeAmplitude(1000)
        assertTrue(mid in 0f..1f)
        val high = VoiceCaptureAmplitude.normalizeAmplitude(32767)
        assertTrue(high >= mid)
        assertTrue(high <= 1f)
    }

    @Test
    fun normalizeAmplitude_maxRawClampsToOne() {
        // 满量程 32767 走 ceiling 钳制，恰好 1f
        assertEquals(1f, VoiceCaptureAmplitude.normalizeAmplitude(32767), 0.0001f)
        assertEquals(1f, VoiceCaptureAmplitude.normalizeAmplitude(Int.MAX_VALUE), 0.0001f)
    }

    @Test
    fun normalizeAmplitude_minPositiveRawIsZero() {
        // 最小正输入 dB=0，归一化后恰好 0f
        assertEquals(0f, VoiceCaptureAmplitude.normalizeAmplitude(1), 0.0001f)
    }

    @Test
    fun normalizeAmplitude_isMonotonicOnSmallRaws() {
        val small = VoiceCaptureAmplitude.normalizeAmplitude(1)
        val mid = VoiceCaptureAmplitude.normalizeAmplitude(2)
        val large = VoiceCaptureAmplitude.normalizeAmplitude(10)
        assertEquals(0f, small, 0.0001f)
        assertTrue(mid > small && mid < 0.2f)
        assertTrue(large > mid && large < 0.6f)
        assertEquals(1f, VoiceCaptureAmplitude.normalizeAmplitude(100), 0.0001f)
    }
}
