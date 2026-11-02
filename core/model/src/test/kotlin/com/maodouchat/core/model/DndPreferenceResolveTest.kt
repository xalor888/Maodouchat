package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DndPreferenceResolveTest {

    @Test
    fun `stored switch always wins`() {
        assertTrue(DndPreferenceResolve.enabled(true, false, false, 22, 7))
        assertFalse(DndPreferenceResolve.enabled(false, true, true, 22, 7))
    }

    @Test
    fun `legacy hours infer only when window is non-empty`() {
        assertFalse(DndPreferenceResolve.enabled(null, false, false, 22, 7))
        assertFalse(DndPreferenceResolve.enabled(null, true, true, 22, 22))
        assertTrue(DndPreferenceResolve.enabled(null, true, false, 22, 7))
    }

    @Test
    fun `minute fallbacks coerce hour ranges`() {
        assertEquals(1380, DndPreferenceResolve.startMinute(null, 99))
        assertEquals(0, DndPreferenceResolve.endMinute(null, -3))
        assertEquals(1439, DndPreferenceResolve.endMinute(9999, 7))
    }
}
