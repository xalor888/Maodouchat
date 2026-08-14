package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdpVideoDetectionTest {
    @Test
    fun `detects active video media line case insensitively`() {
        assertTrue(sdpHasActiveVideo("m=video 9 UDP/TLS/RTP/SAVPF 96"))
        assertTrue(sdpHasActiveVideo("M=video 9 UDP/TLS/RTP/SAVPF 96"))
    }

    @Test
    fun `rejects disabled or non media video text`() {
        assertFalse(sdpHasActiveVideo("m=video 0 UDP/TLS/RTP/SAVPF 96"))
        assertFalse(sdpHasActiveVideo("a=ssrc:1234 m=video"))
        assertFalse(sdpHasActiveVideo(null))
    }
}
