package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DndPreferenceResolveTest {

    @Test
    fun explicitSwitchWinsOverHourWindow() {
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = false,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 22,
                endHour = 7,
            )
        )
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = true,
                startHourPresent = false,
                endHourPresent = false,
                startHour = 22,
                endHour = 22,
            )
        )
    }

    @Test
    fun missingSwitchInfersEnabledFromUnequalHourWindow() {
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 22,
                endHour = 7,
            )
        )
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 22,
                endHour = 22,
            )
        )
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = false,
                endHourPresent = false,
                startHour = 22,
                endHour = 7,
            )
        )
    }

    @Test
    fun minuteFallsBackToHourWhenKeyMissing() {
        assertEquals(22 * 60, DndPreferenceResolve.startMinute(null, 22))
        assertEquals(7 * 60, DndPreferenceResolve.endMinute(null, 7))
        assertEquals(1335, DndPreferenceResolve.startMinute(1335, 22))
        assertEquals(0, DndPreferenceResolve.endMinute(-1, 7))
        assertEquals(1439, DndPreferenceResolve.startMinute(2000, 22))
    }
}
