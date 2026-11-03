package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallTest {

    @Test
    fun `unread is the default`() {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        assertFalse(call.isRead)
    }

    @Test
    fun `copy marks read without changing other fields`() {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "video", receivedAt = 2L)
        val read = call.copy(isRead = true)
        assertTrue(read.isRead)
        assertEquals("m1", read.id)
        assertEquals("video", read.callType)
    }

    @Test
    fun `same fields mean equal`() {
        val a = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        val b = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        assertEquals(a, b)
    }
}
