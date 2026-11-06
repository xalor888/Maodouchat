package com.maodouchat.core.realtime

import com.maodouchat.core.model.WebSocketEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RealtimeTransportModelsTest {
    private fun event(generation: Long = 1L) =
        RealtimeTransportEvent(generation, WebSocketEvent.Disconnected)

    @Test
    fun eventHoldsGenerationAndPayload() {
        val actual = event(7L)
        assertEquals(7L, actual.connectionGeneration)
        assertEquals(WebSocketEvent.Disconnected, actual.event)
    }

    @Test
    fun copyChangesOnlyGeneration() {
        val copied = event(1L).copy(connectionGeneration = 2L)
        assertEquals(2L, copied.connectionGeneration)
        assertEquals(WebSocketEvent.Disconnected, copied.event)
    }

    @Test
    fun eventsWithDifferentGenerationsAreNotEqual() {
        assertNotEquals(event(1L), event(2L))
    }

    @Test
    fun eventsWithSameGenerationAndPayloadAreEqual() {
        assertEquals(event(3L), event(3L))
    }
}
