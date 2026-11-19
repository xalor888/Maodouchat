package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRTCConnectionModelsTest {

    @Test
    fun `group peer connection state has all eight states`() {
        assertEquals(8, GroupPeerConnectionState.entries.size)
        assertTrue(GroupPeerConnectionState.entries.contains(
            GroupPeerConnectionState.NO_ANSWER))
    }

}
