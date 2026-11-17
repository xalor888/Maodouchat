package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentAudioRequestPolicyTest {

    @Test
    fun `transcriptionUrl trims trailing slashes`() {
        assertEquals(
            "https://x/audio/transcriptions",
            AgentAudioRequestPolicy.transcriptionUrl("https://x/"),
        )
        assertEquals(
            "https://x/audio/transcriptions",
            AgentAudioRequestPolicy.transcriptionUrl("https://x"),
        )
    }

    @Test
    fun `defaultMime keeps a given mime and falls back to mp4`() {
        assertEquals("audio/webm", AgentAudioRequestPolicy.defaultMime("audio/webm"))
        assertEquals("audio/mp4", AgentAudioRequestPolicy.defaultMime(""))
        assertEquals("audio/mp4", AgentAudioRequestPolicy.defaultMime("   "))
    }

    @Test
    fun `file name and error strings match the client`() {
        assertEquals("voice.m4a", AgentAudioRequestPolicy.FILE_NAME)
        assertEquals("无法解码语音", AgentAudioRequestPolicy.DECODE_ERROR)
        assertEquals("语音接口没有 text", AgentAudioRequestPolicy.EMPTY_TEXT_ERROR)
    }
}
