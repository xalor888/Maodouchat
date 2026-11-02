package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuietHoursPolicyTest {

    @Test
    fun `disabled window never suppresses`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow.OFF, 720))
    }

    @Test
    fun `start equals end means closed`() {
        val w = QuietWindow(true, 540, 540)
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 540))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        val w = QuietWindow(true, 1320, 420)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 419))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 420))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1319))
    }
}
