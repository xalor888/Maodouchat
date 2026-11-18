package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallUiModelsTest {

    @Test
    fun `call ui state defaults to idle audio call`() {
        val state = CallUiState()
        assertEquals("", state.contactId)
        assertEquals(CallType.AUDIO, state.callType)
        assertEquals(CallState.IDLE, state.callState)
        assertEquals(NetworkQuality.UNKNOWN, state.networkStats)
        assertTrue(state.groupParticipants.isEmpty())
        assertEquals(0, state.nativeDownloadProgress)
        assertNull(state.errorMessage)
    }

    @Test
    fun `webrtc native load exception carries message`() {
        val error = WebRtcNativeLoadException("lib missing")
        assertEquals("lib missing", error.message)
    }
}
