package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReliabilityPolicyTest {
    @Test
    fun `video requires camera and microphone while audio requires microphone`() {
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA),
            CallReliabilityPolicy.requiredPermissions(CallType.VIDEO)
        )
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE),
            CallReliabilityPolicy.requiredPermissions(CallType.AUDIO)
        )
    }

    @Test
    fun `direct call rejects another senders signaling`() {
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(false, "user-a", "user-a"))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(false, "user-a", "user-b"))
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(true, "group-id", "user-b"))
    }

    @Test
    fun `bound signaling session requires exact call id while unbound receiver accepts initial offer`() {
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("call-a", "call-a"))
        assertFalse(CallReliabilityPolicy.shouldAcceptCallId("call-a", "call-b"))
        assertFalse(CallReliabilityPolicy.shouldAcceptCallId("call-a", ""))
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("", "call-a"))
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("", ""))
        assertTrue(CallReliabilityPolicy.shouldAcceptHangUpAction("call-a", "call-a"))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("call-a", ""))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("", "stale-call"))
    }

    @Test
    fun `released call generation cannot affect the next call`() {
        val gate = CallSessionGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.invalidate(first))
        assertFalse(gate.isCurrent(first))

        val second = gate.begin()
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.invalidate(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `audio route prioritizes bluetooth then wired and respects video speaker preference`() {
        assertEquals(
            CallAudioRoute.BLUETOOTH,
            CallAudioRoutePolicy.preferred(setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.WIRED), false)
        )
        assertEquals(
            CallAudioRoute.WIRED,
            CallAudioRoutePolicy.preferred(setOf(CallAudioRoute.WIRED, CallAudioRoute.EARPIECE), false)
        )
        assertEquals(
            CallAudioRoute.SPEAKER,
            CallAudioRoutePolicy.preferred(setOf(CallAudioRoute.SPEAKER, CallAudioRoute.EARPIECE), true)
        )
        assertEquals(
            CallAudioRoute.EARPIECE,
            CallAudioRoutePolicy.selected(
                setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.EARPIECE),
                speakerPreferred = false,
                manualRoute = CallAudioRoute.EARPIECE
            )
        )
        assertEquals(
            CallAudioRoute.BLUETOOTH,
            CallAudioRoutePolicy.selected(
                setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.EARPIECE),
                speakerPreferred = false,
                manualRoute = CallAudioRoute.WIRED
            )
        )
        assertEquals(
            listOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.WIRED, CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
            CallAudioRoutePolicy.candidates(
                linkedSetOf(
                    CallAudioRoute.SPEAKER,
                    CallAudioRoute.EARPIECE,
                    CallAudioRoute.WIRED,
                    CallAudioRoute.BLUETOOTH
                ),
                speakerPreferred = false,
                manualRoute = null
            )
        )
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.BLUETOOTH, CallAudioRoute.SPEAKER),
            CallAudioRoutePolicy.candidates(
                setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
                speakerPreferred = false,
                manualRoute = CallAudioRoute.EARPIECE
            )
        )
    }

    @Test
    fun `signaling types are normalized case-insensitively for REST fallback`() {
        assertEquals("offer", CallReliabilityPolicy.normalizeSignalingType("OFFER"))
        assertEquals("hang-up", CallReliabilityPolicy.normalizeSignalingType(" Hang-Up "))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("ANSWER"))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType(" reject "))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("BUSY"))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("hang-up"))
        assertFalse(CallReliabilityPolicy.isCriticalSignalingType("ice-candidate"))
        assertFalse(CallReliabilityPolicy.isCriticalSignalingType("hangup"))
    }

    @Test
    fun `ice reconnect grace transitions`() {
        assertEquals(IceReconnectAction.START_GRACE, CallReliabilityPolicy.iceReconnectAction("DISCONNECTED"))
        assertEquals(IceReconnectAction.CANCEL_GRACE, CallReliabilityPolicy.iceReconnectAction("connected"))
        assertEquals(IceReconnectAction.CANCEL_GRACE, CallReliabilityPolicy.iceReconnectAction("COMPLETED"))
        // FAILED now attempts ICE restart before giving up
        assertEquals(IceReconnectAction.RESTART_ICE, CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 0))
        assertEquals(IceReconnectAction.RESTART_ICE, CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 1))
        assertEquals(IceReconnectAction.END_NOW, CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 2))
        assertEquals(IceReconnectAction.END_NOW, CallReliabilityPolicy.iceReconnectAction("FAILED", restartAttempts = 99))
        assertEquals(IceReconnectAction.IGNORE, CallReliabilityPolicy.iceReconnectAction("CHECKING"))
        assertEquals(10_000L, CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
    }

    @Test
    fun `network quality buckets from rtt and loss`() {
        assertEquals(CallNetworkQualityPolicy.Level.UNKNOWN, CallNetworkQualityPolicy.fromStats(null, 0.0))
        assertEquals(CallNetworkQualityPolicy.Level.GOOD, CallNetworkQualityPolicy.fromStats(120, 0.5))
        assertEquals(CallNetworkQualityPolicy.Level.FAIR, CallNetworkQualityPolicy.fromStats(350, 2.0))
        assertEquals(CallNetworkQualityPolicy.Level.POOR, CallNetworkQualityPolicy.fromStats(800, 1.0))
        assertEquals(CallNetworkQualityPolicy.Level.POOR, CallNetworkQualityPolicy.fromStats(100, 8.0))
        assertEquals(CallNetworkQualityPolicy.Level.GOOD, CallNetworkQualityPolicy.fromStats(180, null))
        assertEquals(CallNetworkQualityPolicy.Level.FAIR, CallNetworkQualityPolicy.fromStats(400, null))
        assertEquals(CallNetworkQualityPolicy.Level.POOR, CallNetworkQualityPolicy.fromStats(900, null))
    }

    @Test
    fun `group grid and active participant policy stay bounded`() {
        assertEquals(2, GroupCallPolicy.gridColumns(1))
        assertEquals(2, GroupCallPolicy.gridColumns(4))
        assertEquals(3, GroupCallPolicy.gridColumns(5))
        assertEquals(6, GroupCallPolicy.MAX_MESH_MEMBERS)
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.CONNECTING))
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.RECONNECTING))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.REJECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.FAILED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.NO_ANSWER))
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("user-a", "user-b"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("user-b", "user-a"))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-a", listOf("a", "b"), listOf("b", "a")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-b", listOf("a", "b"), listOf("a", "b")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-a", listOf("a", "b"), listOf("a", "c")))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("group-a", "", listOf("a", "b"), emptyList()))
    }
}
