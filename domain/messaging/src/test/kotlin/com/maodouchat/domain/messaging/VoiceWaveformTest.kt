package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceWaveformTest {

    @Test
    fun `waveform has eight bars`() {
        assertEquals(8, waveHeightsFor("msg-1").size)
    }

    @Test
    fun `waveform heights stay within range`() {
        assertTrue(waveHeightsFor("msg-1").all { it in 0.25f..1.0f })
        assertTrue(waveHeightsFor("").all { it in 0.25f..1.0f })
    }

    @Test
    fun `waveform is deterministic per message id`() {
        assertEquals(waveHeightsFor("msg-1"), waveHeightsFor("msg-1"))
    }

    @Test
    fun `different message ids give different waveforms`() {
        assertNotEquals(waveHeightsFor("msg-1"), waveHeightsFor("msg-2"))
    }
}
