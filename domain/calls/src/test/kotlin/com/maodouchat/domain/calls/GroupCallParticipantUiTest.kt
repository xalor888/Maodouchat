package com.maodouchat.domain.calls

import kotlin.test.assertEquals
import kotlin.test.Test

class GroupCallParticipantUiTest {

    @Test
    fun `group participant name defaults to user id`() {
        val participant = GroupCallParticipantUi(userId = "u1")
        assertEquals("u1", participant.name)
        assertEquals(GroupPeerConnectionState.CONNECTING, participant.connectionState)
        assertEquals(false, participant.videoAvailable)
    }

}
