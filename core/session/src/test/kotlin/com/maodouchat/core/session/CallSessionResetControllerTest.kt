package com.maodouchat.core.session

import com.maodouchat.core.model.GroupCallParticipantUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionResetControllerTest {
    private class Rec(
        var ending: Boolean? = null,
        var activeCallId: String? = null,
        var activeGroupId: String? = null,
        var mesh: List<String>? = null,
        var members: Set<String>? = null,
        var offerSdp: String? = null,
        var logOwner: String? = null,
    )

    private fun controller(rec: Rec, owner: String = "self-1"): CallSessionResetController =
        CallSessionResetController(
            beginCallSession = { _, _ -> 42L },
            newCallId = { "call-9" },
            setEndingCall = { rec.ending = it },
            setActiveCallId = { rec.activeCallId = it },
            setActiveGroupId = { rec.activeGroupId = it },
            setMeshGroupMemberIds = { rec.mesh = it },
            setGroupMemberIds = { rec.members = it },
            setPendingOfferSdp = { rec.offerSdp = it },
            setCallLogOwnerUserId = { rec.logOwner = it },
            ownerUserId = { owner },
        )

    @Test
    fun outgoingResetsStateAndSnapshotsInjectedOwner() {
        val rec = Rec()
        val session = controller(rec).resetForNewOutgoingCall("peer-1")
        assertEquals(42L, session)
        assertEquals(false, rec.ending)
        assertEquals("call-9", rec.activeCallId)
        assertEquals("", rec.activeGroupId)
        assertEquals(emptyList<String>(), rec.mesh)
        assertEquals("self-1", rec.logOwner)
    }

    @Test
    fun groupCallSortsMeshMembers() {
        val rec = Rec()
        controller(rec).resetForNewGroupCall("g-1", listOf("u2", "u3"), "self-1")
        assertEquals("g-1", rec.activeGroupId)
        assertEquals(listOf("self-1", "u2", "u3"), rec.mesh)
    }

    @Test
    fun incomingGroupCallExcludesSelfFromMembers() {
        val rec = Rec()
        val (isGroup, members) = controller(rec).resetForNewIncomingCall(
            contactId = "u2",
            offerSdp = "sdp",
            callId = "call-9",
            groupId = "g-1",
            groupMemberIds = listOf("self-1", "u2", "u3", " ", "u2"),
        )
        assertTrue(isGroup)
        assertEquals("sdp", rec.offerSdp)
        assertEquals("g-1", rec.activeGroupId)
        // mesh 含 self（与 resetForNewGroupCall 的排序一致），排 self 的是 members
        assertEquals(listOf("self-1", "u2", "u3"), rec.mesh)
        assertEquals(setOf("u2", "u3"), rec.members)
        assertEquals(listOf(GroupCallParticipantUi("u2"), GroupCallParticipantUi("u3")), members)
    }

    @Test
    fun incomingOneToOneCallClearsGroupState() {
        val rec = Rec()
        val (isGroup, members) = controller(rec).resetForNewIncomingCall(
            contactId = "u2",
            offerSdp = "sdp",
            callId = "call-9",
            groupId = "",
            groupMemberIds = listOf("self-1", "u2"),
        )
        assertFalse(isGroup)
        assertEquals("", rec.activeGroupId)
        assertEquals(emptyList<String>(), rec.mesh)
        assertEquals(emptySet<String>(), rec.members)
        assertTrue(members.isEmpty())
    }

    @Test
    fun markIncomingSessionOwnerUsesInjectedOwner() {
        val rec = Rec()
        controller(rec, owner = "self-7").markIncomingSessionOwner()
        assertEquals("self-7", rec.logOwner)
    }
}
