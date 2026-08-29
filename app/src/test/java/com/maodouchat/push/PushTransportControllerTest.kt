package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PushTransportControllerTest {
    @Test
    fun missingSessionDoesNotOpenSocket() {
        var connects = 0
        val transport = PushTransportController(
            session = { null },
            isConnected = { false },
            socket = PushSocketPort { _, _ -> connects++ },
            url = "wss://example.test/ws",
        )

        assertEquals(PushTransportState.NoSession, transport.ensureForegroundConnection())
        assertEquals(0, connects)
    }

    @Test
    fun validSessionConnectsOnceWhenSocketIsDown() {
        var connected = false
        var connects = 0
        val transport = PushTransportController(
            session = { "owner" to "token" },
            isConnected = { connected },
            socket = PushSocketPort { _, _ ->
                connects++
                connected = true
            },
            url = "wss://example.test/ws",
        )

        assertIs<PushTransportState.Connecting>(transport.ensureForegroundConnection())
        assertIs<PushTransportState.Connected>(transport.ensureForegroundConnection())
        assertEquals(1, connects)
    }
}
