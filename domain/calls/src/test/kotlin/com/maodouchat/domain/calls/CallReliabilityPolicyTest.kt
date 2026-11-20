package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallReliabilityPolicyTest {
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
        assertEquals(10_000L, ICE_RECONNECT_GRACE_MS)
    }

    @Test
    fun `ice reconnect handles lowercase states negative attempts and unknown states`() {
        assertEquals(IceReconnectAction.START_GRACE, CallReliabilityPolicy.iceReconnectAction("disconnected"))
        assertEquals(IceReconnectAction.RESTART_ICE, CallReliabilityPolicy.iceReconnectAction("failed", restartAttempts = -1))
        assertEquals(IceReconnectAction.IGNORE, CallReliabilityPolicy.iceReconnectAction("gathering"))
        assertEquals(2, ICE_MAX_RESTART_ATTEMPTS)
        assertEquals(5_000L, ICE_RESTART_INTERVAL_MS)
    }

    @Test
    fun `hang-up action with both ids blank passes exact equality`() {
        // 挂断要求精确相等，空白 == 空白也算相等（与初始信令的放行语义不同）
        assertTrue(CallReliabilityPolicy.shouldAcceptHangUpAction("", ""))
    }

    @Test
    fun `disconnected and connected grace ignore restart attempts`() {
        assertEquals(
            IceReconnectAction.START_GRACE,
            CallReliabilityPolicy.iceReconnectAction("DISCONNECTED", restartAttempts = 99)
        )
        assertEquals(
            IceReconnectAction.CANCEL_GRACE,
            CallReliabilityPolicy.iceReconnectAction("connected", restartAttempts = 99)
        )
        assertEquals(
            IceReconnectAction.CANCEL_GRACE,
            CallReliabilityPolicy.iceReconnectAction("COMPLETED", restartAttempts = 5)
        )
    }

    @Test
    fun `ice state with surrounding whitespace is not recognized`() {
        // 状态只做 uppercase，不 trim：带空白的状态落到 IGNORE
        assertEquals(IceReconnectAction.IGNORE, CallReliabilityPolicy.iceReconnectAction(" disconnected "))
        assertEquals(IceReconnectAction.IGNORE, CallReliabilityPolicy.iceReconnectAction("failed "))
        assertEquals(IceReconnectAction.IGNORE, CallReliabilityPolicy.iceReconnectAction(""))
    }

    @Test
    fun `hang-up action requires exact call id`() {
        assertTrue(CallReliabilityPolicy.shouldAcceptHangUpAction("call-a", "call-a"))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("call-a", ""))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("", "stale-call"))
    }

    @Test
    fun `critical signaling types are recognized`() {
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("ANSWER"))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType(" reject "))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("BUSY"))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("hang-up"))
        assertFalse(CallReliabilityPolicy.isCriticalSignalingType("ice-candidate"))
        assertFalse(CallReliabilityPolicy.isCriticalSignalingType("hangup"))
    }

    @Test
    fun `group peer active states`() {
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.CONNECTING))
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.RECONNECTING))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.REJECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.FAILED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.NO_ANSWER))
    }

    @Test
    fun `mesh edge initiation requires ordered user ids`() {
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("user-a", "user-b"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("user-b", "user-a"))
    }

    @Test
    fun `group peer active states pin edges`() {
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.CONNECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.DISCONNECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.BUSY))
    }

    @Test
    fun `mesh edge initiation rejects blank and self edges`() {
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("", "user-b"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("user-a", "user-a"))
    }

    @Test
    fun `ice reconnect actions keep declaration order and valueOf round trip`() {
        val actions = IceReconnectAction.entries.toList()
        assertEquals(5, actions.size)
        assertEquals(IceReconnectAction.RESTART_ICE, IceReconnectAction.valueOf("RESTART_ICE"))
        assertEquals(IceReconnectAction.IGNORE, actions.last())
    }

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
    }

    @Test
    fun `signaling types are normalized case-insensitively for REST fallback`() {
        assertEquals("offer", CallReliabilityPolicy.normalizeSignalingType("OFFER"))
        assertEquals("hang-up", CallReliabilityPolicy.normalizeSignalingType(" Hang-Up "))
    }

    @Test
    fun `group grid and active participant policy stay bounded`() {
        assertEquals(2, GroupCallPolicy.gridColumns(1))
        assertEquals(2, GroupCallPolicy.gridColumns(4))
        assertEquals(3, GroupCallPolicy.gridColumns(5))
        assertEquals(6, GroupCallPolicy.MAX_MESH_MEMBERS)
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-a", listOf("a", "b"), listOf("b", "a")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-b", listOf("a", "b"), listOf("a", "b")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("group-a", "group-a", listOf("a", "b"), listOf("a", "c")))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("group-a", "", listOf("a", "b"), emptyList()))
    }

    @Test
    fun `group call requires microphone only`() {
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE),
            CallReliabilityPolicy.requiredPermissions(CallType.GROUP)
        )
    }

    @Test
    fun `blank sender is rejected even for group calls and blank expected contact accepts any sender`() {
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(true, "user-a", ""))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(true, "", ""))
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(false, "", "user-b"))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(false, "", ""))
    }

    @Test
    fun `group grid mesh and active states pin their edges`() {
        assertEquals(2, GroupCallPolicy.gridColumns(0))
        assertEquals(3, GroupCallPolicy.gridColumns(100))
    }

    @Test
    fun `whitespace-only sender is rejected even for group calls`() {
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(true, "user-a", "   "))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(false, "", "   "))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(true, "", "   "))
    }

    @Test
    fun `group metadata blank active id accepts and member sets compare as sets`() {
        assertTrue(
            GroupCallPolicy.shouldAcceptMetadata(
                "", "group-b", listOf("a"), listOf("x", "y")
            )
        )
        assertTrue(
            GroupCallPolicy.shouldAcceptMetadata(
                "group-a", "group-a", listOf("a", "a", "b"), listOf("b", "a")
            )
        )
        assertFalse(
            GroupCallPolicy.shouldAcceptMetadata(
                "group-a", "group-a", listOf("a", "b"), listOf("a")
            )
        )
    }

}
