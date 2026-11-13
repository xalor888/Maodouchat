package com.maodouchat.domain.messaging

import com.maodouchat.core.model.QuietWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursPresetTest {

    @Test
    fun presetsCarryExpectedMinutes() {
        val cases = listOf(
            QuietHoursPreset.Night to (22 * 60 to 7 * 60),
            QuietHoursPreset.Lunch to (12 * 60 to 14 * 60),
            QuietHoursPreset.Sleep to (23 * 60 to 8 * 60),
            QuietHoursPreset.Workday to (9 * 60 to 18 * 60),
        )
        for ((preset, expected) in cases) {
            assertEquals(expected, preset.startMinute to preset.endMinute, preset.name)
        }
    }

    @Test
    fun matchesEnabledWindowWithSameMinutes() {
        assertTrue(QuietHoursPreset.Night.matches(QuietWindow(true, 22 * 60, 7 * 60)))
    }

    @Test
    fun doesNotMatchDisabledOrDifferentWindow() {
        assertFalse(QuietHoursPreset.Night.matches(QuietWindow(false, 22 * 60, 7 * 60)))
        assertFalse(QuietHoursPreset.Night.matches(QuietWindow(true, 21 * 60, 7 * 60)))
        assertFalse(QuietHoursPreset.Night.matches(QuietWindow(true, 22 * 60, 8 * 60)))
        assertFalse(QuietHoursPreset.Night.matches(QuietWindow(true, 12 * 60, 14 * 60)))
    }

    @Test
    fun toWindowIsEnabledWithPresetMinutes() {
        assertEquals(QuietWindow(true, 23 * 60, 8 * 60), QuietHoursPreset.Sleep.toWindow())
    }

    @Test
    fun fourPresetsInOrder() {
        assertEquals(
            listOf(
                QuietHoursPreset.Night,
                QuietHoursPreset.Lunch,
                QuietHoursPreset.Sleep,
                QuietHoursPreset.Workday,
            ),
            QuietHoursPreset.entries,
        )
    }
}
