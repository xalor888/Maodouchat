package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QuietWindowTest {

    @Test
    fun `OFF is disabled and zeroed`() {
        assertFalse(QuietWindow.OFF.enabled)
        assertEquals(0, QuietWindow.OFF.startMinute)
        assertEquals(0, QuietWindow.OFF.endMinute)
    }

    @Test
    fun `copy keeps fields`() {
        val w = QuietWindow(true, 1320, 420)
        assertEquals(1320, w.copy().startMinute)
        assertEquals(w, QuietWindow(true, 1320, 420))
    }
}
