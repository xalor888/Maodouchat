package com.maodouchat.util

import com.maodouchat.core.model.HoldHint
import com.maodouchat.core.model.VoiceCapturePolicy
import com.maodouchat.core.model.VoiceRecordingWaveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapturePolicyTest {


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
    fun previewPolicy_negativeDuration() {
        assertFalse(VoiceCapturePolicy.canEnterPreview(-1L))
        assertFalse(VoiceCapturePolicy.canSendPreview(-100L))
    }




    @Test
    fun waveform_clampsOutOfRangePushes() {
        val wave = VoiceRecordingWaveform(capacity = 4)
        wave.push(-0.5f)
        wave.push(1.5f)
        val snap = wave.snapshot()
        assertEquals(0f, snap[0], 0.0001f)
        assertEquals(0f, snap[1], 0.0001f)
        assertEquals(0f, snap[2], 0.0001f)
        assertEquals(1f, snap[3], 0.0001f)
    }

    @Test
    fun waveform_capacityOneKeepsOnlyLatest() {
        val wave = VoiceRecordingWaveform(capacity = 1)
        wave.push(0.2f)
        wave.push(0.7f)
        val snap = wave.snapshot()
        assertEquals(1, snap.size)
        assertEquals(0.7f, snap[0], 0.0001f)
    }

    @Test
    fun previewPolicy_sendSharesEnterBoundary() {
        assertFalse(VoiceCapturePolicy.canSendPreview(499))
        assertTrue(VoiceCapturePolicy.canEnterPreview(Long.MAX_VALUE))
        assertTrue(VoiceCapturePolicy.canSendPreview(Long.MAX_VALUE))
    }


    @Test
    fun waveform_defaultCapacityIsFiftySixAndEmpty() {
        assertEquals(56, VoiceRecordingWaveform.DEFAULT_CAPACITY)
        val wave = VoiceRecordingWaveform()
        val snap = wave.snapshot()
        assertEquals(56, snap.size)
        assertTrue(snap.all { it == 0f })
    }

    @Test
    fun waveform_capacityTwoRotatesOnThirdPush() {
        val wave = VoiceRecordingWaveform(capacity = 2)
        wave.push(0.1f)
        wave.push(0.2f)
        assertEquals(0.1f, wave.snapshot()[0], 0.0001f)
        assertEquals(0.2f, wave.snapshot()[1], 0.0001f)
        wave.push(0.3f)
        val snap = wave.snapshot()
        assertEquals(0.2f, snap[0], 0.0001f)
        assertEquals(0.3f, snap[1], 0.0001f)
    }

    @Test
    fun waveform_singlePushRightAligns() {
        val wave = VoiceRecordingWaveform(capacity = 3)
        wave.push(0.7f)
        val snap = wave.snapshot()
        assertEquals(0f, snap[0], 0.0001f)
        assertEquals(0f, snap[1], 0.0001f)
        assertEquals(0.7f, snap[2], 0.0001f)
        wave.push(0.8f)
        val snap2 = wave.snapshot()
        assertEquals(0f, snap2[0], 0.0001f)
        assertEquals(0.7f, snap2[1], 0.0001f)
        assertEquals(0.8f, snap2[2], 0.0001f)
    }


    @Test
    fun previewPolicy_minSendMsConstantPinned() {
        assertEquals(500L, VoiceCapturePolicy.MIN_SEND_MS)
        assertTrue(VoiceCapturePolicy.canEnterPreview(VoiceCapturePolicy.MIN_SEND_MS))
        assertTrue(VoiceCapturePolicy.canSendPreview(VoiceCapturePolicy.MIN_SEND_MS))
        assertFalse(VoiceCapturePolicy.canEnterPreview(VoiceCapturePolicy.MIN_SEND_MS - 1))
        assertFalse(VoiceCapturePolicy.canSendPreview(VoiceCapturePolicy.MIN_SEND_MS - 1))
    }
}
