package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class PushTransportModelsTest {

    @Test
    fun `states carry their owner`() {
        assertEquals(
            "u1",
            (PushTransportState.Connected("u1") as PushTransportState.Connected).ownerUserId,
        )
        assertTrue(PushTransportState.NoSession is PushTransportState)
        assertTrue(PushTransportState.Stopped is PushTransportState)
    }

    @Test
    fun `connecting keeps owner distinct from connected`() {
        val state: PushTransportState = PushTransportState.Connecting("u2")
        assertTrue(state is PushTransportState.Connecting)
        assertEquals("u2", (state as PushTransportState.Connecting).ownerUserId)
    }
}
