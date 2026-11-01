package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUiResetEventTest {

    @Test
    fun unreadBadgeEventExists() {
        assertTrue(SessionUiResetEvent.entries.contains(SessionUiResetEvent.UNREAD_BADGE))
    }

    @Test
    fun eventResolvesByName() {
        assertEquals(SessionUiResetEvent.UNREAD_BADGE, SessionUiResetEvent.valueOf("UNREAD_BADGE"))
    }
}
