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
    fun explicitSwitchShortCircuitsClampedHours() {
        // 显式开关直接返回：越界小时的钳制不影响它。
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = false,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 99,
                endHour = -5,
            )
        )
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = true,
                startHourPresent = false,
                endHourPresent = false,
                startHour = 99,
                endHour = -5,
            )
        )
    }

    @Test
    fun singleHourKeyPresentStillInfersEnabled() {
        // 只有一个小时 key 写过，仍按 start!=end 推断（与旧版遗留数据兼容）。
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = false,
                startHour = 22,
                endHour = 22,
            )
        )
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = false,
                startHour = 22,
                endHour = 7,
            )
        )
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = false,
                endHourPresent = true,
                startHour = 22,
                endHour = 7,
            )
        )
    }

    @Test
    fun outOfRangeHoursClampedBeforeEqualityCheck() {
        // 先钳制到 0..23 再判 start==end：24→23 与 23 判成关闭。
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 24,
                endHour = 23,
            )
        )
        assertTrue(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 22,
                endHour = 100,
            )
        )
        assertFalse(
            DndPreferenceResolve.enabled(
                enabledStored = null,
                startHourPresent = true,
                endHourPresent = true,
                startHour = 0,
                endHour = 0,
            )
        )
    }

    @Test
    fun hourFallbackClampsIntoDayRange() {
        assertEquals(1380, DndPreferenceResolve.startMinute(null, 99))
        assertEquals(0, DndPreferenceResolve.startMinute(null, -3))
        assertEquals(1380, DndPreferenceResolve.endMinute(null, 25))
        assertEquals(0, DndPreferenceResolve.startMinute(-50, 22))
        assertEquals(1439, DndPreferenceResolve.endMinute(9999, 7))
    }

    @Test
    fun resolutionIsDeterministic() {
        assertEquals(
            DndPreferenceResolve.enabled(null, true, true, 22, 7),
            DndPreferenceResolve.enabled(null, true, true, 22, 7)
        )
        assertEquals(
            DndPreferenceResolve.startMinute(null, 22),
            DndPreferenceResolve.startMinute(null, 22)
        )
        assertEquals(
            DndPreferenceResolve.endMinute(1335, 7),
            DndPreferenceResolve.endMinute(1335, 7)
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
