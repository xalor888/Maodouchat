package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupParticipantStatusKindTest {

    @Test
    fun `all eight states map one to one`() {
        assertEquals(GroupParticipantStatusKind.CONNECTING,
            groupParticipantStatusKind(GroupPeerConnectionState.CONNECTING))
        assertEquals(GroupParticipantStatusKind.CONNECTED,
            groupParticipantStatusKind(GroupPeerConnectionState.CONNECTED))
        assertEquals(GroupParticipantStatusKind.RECONNECTING,
            groupParticipantStatusKind(GroupPeerConnectionState.RECONNECTING))
        assertEquals(GroupParticipantStatusKind.DISCONNECTED,
            groupParticipantStatusKind(GroupPeerConnectionState.DISCONNECTED))
        assertEquals(GroupParticipantStatusKind.FAILED,
            groupParticipantStatusKind(GroupPeerConnectionState.FAILED))
        assertEquals(GroupParticipantStatusKind.REJECTED,
            groupParticipantStatusKind(GroupPeerConnectionState.REJECTED))
        assertEquals(GroupParticipantStatusKind.BUSY,
            groupParticipantStatusKind(GroupPeerConnectionState.BUSY))
        assertEquals(GroupParticipantStatusKind.NO_ANSWER,
            groupParticipantStatusKind(GroupPeerConnectionState.NO_ANSWER))
    }
}
