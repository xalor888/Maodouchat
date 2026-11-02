package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlayerStateTest {

    @Test
    fun `defaults describe an idle player`() {
        val state = VoicePlayerState()
        assertEquals(null, state.messageId)
        assertEquals(false, state.isPlaying)
        assertEquals(1f, state.speed)
        assertEquals(false, state.earpiece)
    }

    @Test
    fun `copy changes only the given fields`() {
        val playing = VoicePlayerState(messageId = "m1", isPlaying = true, speed = 1.5f, earpiece = true)
        assertEquals("m1", playing.messageId)
        assertEquals(1.5f, playing.speed)
        assertEquals(true, playing.earpiece)
        assertEquals(0L, playing.durationMs)
    }
}
