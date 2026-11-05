package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `group participant name defaults to user id`() {
        val participant = GroupCallParticipantUi(userId = "u1")
        assertEquals("u1", participant.name)
        assertEquals(GroupPeerConnectionState.CONNECTING, participant.connectionState)
        assertEquals(false, participant.videoAvailable)
    }

    @Test
    fun `webrtc native load exception carries message`() {
        val error = WebRtcNativeLoadException("lib missing")
        assertEquals("lib missing", error.message)
    }
}
