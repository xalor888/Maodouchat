package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallReliabilityModelsTest {

    @Test
    fun `media permissions keep declaration order`() {
        assertEquals(
            listOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA),
            CallMediaPermission.entries.toList()
        )
    }

    @Test
    fun `ice reconnect actions keep declaration order and valueOf round trip`() {
        val actions = IceReconnectAction.entries.toList()
        assertEquals(5, actions.size)
        assertEquals(IceReconnectAction.RESTART_ICE, IceReconnectAction.valueOf("RESTART_ICE"))
        assertEquals(IceReconnectAction.IGNORE, actions.last())
    }
}
