package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRTCConnectionModelsTest {

    @Test
    fun `webrtc exception keeps message and is illegal state`() {
        val error = WebRTCException("peer connection unavailable")
        assertEquals("peer connection unavailable", error.message)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `group peer connection state has all eight states`() {
        assertEquals(8, GroupPeerConnectionState.entries.size)
        assertTrue(GroupPeerConnectionState.entries.contains(
            GroupPeerConnectionState.NO_ANSWER))
    }
}
