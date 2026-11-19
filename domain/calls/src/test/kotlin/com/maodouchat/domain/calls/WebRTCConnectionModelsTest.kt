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
}
