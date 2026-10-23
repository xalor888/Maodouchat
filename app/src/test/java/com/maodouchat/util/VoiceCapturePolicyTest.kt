package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapturePolicyTest {

    @Test
    fun normalizeAmplitude_zeroAndPositive() {
        assertEquals(0f, VoiceRecorder.normalizeAmplitude(0), 0.0001f)
        assertEquals(0f, VoiceRecorder.normalizeAmplitude(-3), 0.0001f)
        val mid = VoiceRecorder.normalizeAmplitude(1000)
        assertTrue(mid in 0f..1f)
        val high = VoiceRecorder.normalizeAmplitude(32767)
        assertTrue(high >= mid)
        assertTrue(high <= 1f)
    }

    @Test
    fun waveform_pushAndSnapshot_oldestToNewest() {
        val wave = VoiceRecordingWaveform(capacity = 4)
        wave.push(0.1f)
        wave.push(0.2f)
        wave.push(0.3f)
        val snap = wave.snapshot()
        assertEquals(4, snap.size)
        // 未填满时靠右对齐：前导 0
        assertEquals(0f, snap[0], 0.0001f)
        assertEquals(0.1f, snap[1], 0.0001f)
        assertEquals(0.2f, snap[2], 0.0001f)
        assertEquals(0.3f, snap[3], 0.0001f)
        wave.push(0.4f)
        wave.push(0.5f) // 覆盖最旧 0.1
        val full = wave.snapshot()
        assertEquals(0.2f, full[0], 0.0001f)
        assertEquals(0.3f, full[1], 0.0001f)
        assertEquals(0.4f, full[2], 0.0001f)
        assertEquals(0.5f, full[3], 0.0001f)
        wave.clear()
        assertTrue(wave.snapshot().all { it == 0f })
    }

    @Test
    fun previewPolicy_minDuration() {
        assertFalse(VoiceCapturePolicy.canEnterPreview(499))
        assertTrue(VoiceCapturePolicy.canEnterPreview(500))
        assertTrue(VoiceCapturePolicy.canSendPreview(500))
        assertFalse(VoiceCapturePolicy.canSendPreview(0))
    }

    @Test
    fun holdHint_mapsCancelArmed() {
        assertEquals(
            HoldHint.RELEASE_TO_PREVIEW,
            VoiceCapturePolicy.holdHint(cancelArmed = false),
        )
        assertEquals(
            HoldHint.SLIDE_UP_CANCEL,
            VoiceCapturePolicy.holdHint(cancelArmed = true),
        )
    }

    @Test
    fun playerSpeed_cycles() {
        assertEquals(1.5f, VoicePlayer.nextSpeed(1f), 0.0001f)
        assertEquals(2f, VoicePlayer.nextSpeed(1.5f), 0.0001f)
        assertEquals(0.5f, VoicePlayer.nextSpeed(2f), 0.0001f)
        assertEquals(1f, VoicePlayer.nextSpeed(0.5f), 0.0001f)
        assertEquals(1.5f, VoicePlayer.nextSpeed(0.75f), 0.0001f) // unknown → treat as index 0
        assertEquals("1.5x", VoicePlayer.formatSpeedLabel(1.5f))
        assertEquals("2x", VoicePlayer.formatSpeedLabel(2f))
        assertEquals("0.5x", VoicePlayer.formatSpeedLabel(0.5f))
        assertEquals("1x", VoicePlayer.formatSpeedLabel(1f))
    }

    @Test
    fun formatDuration_minutesSeconds() {
        assertEquals("0:00", VoiceRecorder.formatDuration(0))
        assertEquals("0:05", VoiceRecorder.formatDuration(5_000))
        assertEquals("1:01", VoiceRecorder.formatDuration(61_000))
    }

    @Test
    fun normalizeAmplitude_maxRawClampsToOne() {
        // 满量程 32767 走 ceiling 钳制，恰好 1f
        assertEquals(1f, VoiceRecorder.normalizeAmplitude(32767), 0.0001f)
        assertEquals(1f, VoiceRecorder.normalizeAmplitude(Int.MAX_VALUE), 0.0001f)
    }

    @Test
    fun normalizeAmplitude_minPositiveRawIsZero() {
        // 最小正输入 dB=0，归一化后恰好 0f
        assertEquals(0f, VoiceRecorder.normalizeAmplitude(1), 0.0001f)
    }

    @Test
    fun previewPolicy_negativeDuration() {
        assertFalse(VoiceCapturePolicy.canEnterPreview(-1L))
        assertFalse(VoiceCapturePolicy.canSendPreview(-100L))
    }

    @Test
    fun formatDuration_hourBoundary() {
        // 无小时位：59 分与 60 分边界逐字钉住
        assertEquals("59:59", VoiceRecorder.formatDuration(3_599_999))
        assertEquals("60:00", VoiceRecorder.formatDuration(3_600_000))
    }

    @Test
    fun formatDuration_truncatesSubSecond() {
        assertEquals("0:00", VoiceRecorder.formatDuration(999))
        assertEquals("0:01", VoiceRecorder.formatDuration(1_500))
    }
}
