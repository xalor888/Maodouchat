package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecordingWaveformTest {

    @Test
    fun `snapshot returns oldest to newest and pads unfilled with zero`() {
        val wave = VoiceRecordingWaveform(capacity = 4)
        wave.push(0.25f)
        wave.push(0.5f)
        assertEquals(listOf(0f, 0f, 0.25f, 0.5f), wave.snapshot().toList())
    }

    @Test
    fun `push clamps to zero one and wraps around`() {
        val wave = VoiceRecordingWaveform(capacity = 2)
        wave.push(-1f)
        wave.push(2f)
        wave.push(0.5f)
        assertEquals(listOf(1f, 0.5f), wave.snapshot().toList())
    }

    @Test
    fun `capture policy gates preview on 500ms and maps hold hint`() {
        assertFalse(VoiceCapturePolicy.canEnterPreview(499))
        assertTrue(VoiceCapturePolicy.canEnterPreview(500))
        assertFalse(VoiceCapturePolicy.canSendPreview(0))
        assertEquals(HoldHint.RELEASE_TO_PREVIEW, VoiceCapturePolicy.holdHint(cancelArmed = false))
        assertEquals(HoldHint.SLIDE_UP_CANCEL, VoiceCapturePolicy.holdHint(cancelArmed = true))
    }
}
