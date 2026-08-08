package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class CallReliabilityPolicyIceRestartTest {

    @Test
    fun `FAILED triggers RESTART_ICE on first attempt`() {
        assertEquals(
            IceReconnectAction.RESTART_ICE,
            CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 0)
        )
    }

    @Test
    fun `FAILED triggers RESTART_ICE on second attempt`() {
        assertEquals(
            IceReconnectAction.RESTART_ICE,
            CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 1)
        )
    }

    @Test
    fun `FAILED triggers END_NOW after max restart attempts`() {
        assertEquals(
            IceReconnectAction.END_NOW,
            CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 2)
        )
    }

    @Test
    fun `FAILED triggers END_NOW when attempts exceed max`() {
        assertEquals(
            IceReconnectAction.END_NOW,
            CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 99)
        )
    }

    @Test
    fun `DISCONNECTED still triggers START_GRACE regardless of restart attempts`() {
        assertEquals(
            IceReconnectAction.START_GRACE,
            CallReliabilityPolicy.iceReconnectAction("DISCONNECTED", restartAttempts = 5)
        )
    }

    @Test
    fun `CONNECTED cancels grace regardless of restart attempts`() {
        assertEquals(
            IceReconnectAction.CANCEL_GRACE,
            CallReliabilityPolicy.iceReconnectAction("CONNECTED", restartAttempts = 1)
        )
    }

    @Test
    fun `max restart attempts is 2`() {
        assertEquals(2, CallReliabilityPolicy.ICE_MAX_RESTART_ATTEMPTS)
    }

    @Test
    fun `restart interval is 5 seconds`() {
        assertEquals(5_000L, CallReliabilityPolicy.ICE_RESTART_INTERVAL_MS)
    }

    @Test
    fun `grace period is 10 seconds`() {
        assertEquals(10_000L, CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
    }

    @Test
    fun `case insensitive state matching`() {
        assertEquals(
            IceReconnectAction.RESTART_ICE,
            CallReliabilityPolicy.iceReconnectAction("failed", restartAttempts = 0)
        )
        assertEquals(
            IceReconnectAction.START_GRACE,
            CallReliabilityPolicy.iceReconnectAction("disconnected", restartAttempts = 0)
        )
    }
}
