package com.maodouchat.core.model

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
