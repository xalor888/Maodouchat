package com.maodouchat.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallIceServerTest {
    @Test
    fun isStunOnly_trueForDefaultStun() {
        assertTrue(CallIceServer.isStunOnly(CallIceServer.defaultStun()))
        assertTrue(CallIceServer.isStunOnly(emptyList()))
    }

    @Test
    fun isStunOnly_falseWhenTurnPresent() {
        val mixed = listOf(
            CallIceServer(listOf("stun:stun.example:3478")),
            CallIceServer(listOf("turn:turn.example:3478"), username = "u", credential = "p")
        )
        assertFalse(CallIceServer.isStunOnly(mixed))
        assertFalse(
            CallIceServer.isStunOnly(
                listOf(CallIceServer(listOf("turns:turn.example:5349"), "u", "p"))
            )
        )
    }
}
