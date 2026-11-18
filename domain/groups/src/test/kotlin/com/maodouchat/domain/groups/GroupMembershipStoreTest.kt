package com.maodouchat.domain.groups

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupMembershipStoreTest {

    @Test
    fun membershipStoreUpdateAndOnlineStatus() = runTest {
        val store = InMemoryGroupMembershipStore()
        assertNull(store.getSnapshot("c1"))
        store.updateMembers(
            "c1",
            listOf(GroupMemberUi("u1", "A"), GroupMemberUi("u2", "B")),
            revision = 3L,
        )
        val snap = store.getSnapshot("c1")
        assertEquals(3L, snap?.memberRevision)
        assertEquals(listOf("u1", "u2"), snap?.members?.map { it.userId })
        store.updateMemberOnlineStatus("c1", "u2", true)
        val members = store.getSnapshot("c1")?.members.orEmpty()
        assertTrue(members.first { it.userId == "u2" }.isOnline)
        assertFalse(members.first { it.userId == "u1" }.isOnline)
        store.invalidate("c1")
        assertNull(store.getSnapshot("c1"))
    }
}
