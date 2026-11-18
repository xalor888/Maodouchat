package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallUiModelsTest {

    @Test
    fun `group participant name defaults to user id`() {
        val participant = GroupCallParticipantUi(userId = "u1")
        assertEquals("u1", participant.name)
        assertEquals(GroupPeerConnectionState.CONNECTING, participant.connectionState)
        assertEquals(false, participant.videoAvailable)
    }

}
