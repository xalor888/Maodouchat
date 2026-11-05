package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateModelsTest {

    @Test
    fun `call state has all five states`() {
        assertEquals(5, CallState.entries.size)
        assertEquals(CallState.CONNECTED, CallState.valueOf("CONNECTED"))
    }

    @Test
    fun `detect from sdp returns audio for null blank and audio only`() {
        assertEquals(CallType.AUDIO, CallType.detectFromSdp(null))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("   "))
        assertEquals(
            CallType.AUDIO,
            CallType.detectFromSdp("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        )
    }

    @Test
    fun `detect from sdp returns video only for active video media line`() {
        assertEquals(
            CallType.VIDEO,
            CallType.detectFromSdp("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
        )
        assertEquals(
            CallType.AUDIO,
            CallType.detectFromSdp("v=0\r\nm=video 0 UDP/TLS/RTP/SAVPF 96\r\n")
        )
        assertEquals(
            CallType.AUDIO,
            CallType.detectFromSdp("v=0\r\na=rtpmap:96 H264/90000\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        )
    }
}
