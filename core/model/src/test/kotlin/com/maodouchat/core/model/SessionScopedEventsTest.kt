package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScopedEventsTest {

    @Test
    fun `incoming call wake defaults to generation zero`() {
        val wake = IncomingCallWake(callId = "c1", senderId = "u1")
        assertEquals(0L, wake.sessionGeneration)
        assertEquals(0L, wake.requestId)
        assertFalse(wake.autoAnswer)
        assertTrue(wake is SessionScopedEvent)
    }

    @Test
    fun `missed calls request keeps fields and copy changes generation`() {
        val req = OpenMissedCallsRequest(atMillis = 1L, sessionGeneration = 7L, requestId = -1L)
        assertEquals(7L, req.sessionGeneration)
        assertEquals(8L, req.copy(sessionGeneration = 8L).sessionGeneration)
        assertTrue(req is SessionScopedEvent)
    }

    @Test
    fun `contacts request keeps fields`() {
        val req = OpenContactsRequest(atMillis = 2L, sessionGeneration = 3L, requestId = 4L)
        assertEquals(2L, req.atMillis)
        assertEquals(3L, req.sessionGeneration)
        assertEquals(4L, req.requestId)
    }

}
