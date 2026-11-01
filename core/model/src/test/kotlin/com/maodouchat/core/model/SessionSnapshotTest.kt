package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionSnapshotTest {

    @Test
    fun `equality is by value`() {
        assertEquals(SessionSnapshot("tok", "u1"), SessionSnapshot("tok", "u1"))
        assertNotEquals(SessionSnapshot("tok", "u1"), SessionSnapshot("tok", "u2"))
        assertNotEquals(SessionSnapshot("tok", "u1"), SessionSnapshot(null, null))
    }

    @Test
    fun `fields are kept verbatim`() {
        val snapshot = SessionSnapshot("tok", "u1")
        assertEquals("tok", snapshot.token)
        assertEquals("u1", snapshot.userId)
        assertEquals(SessionSnapshot(null, null), snapshot.copy(token = null, userId = null))
    }
}
