package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilentUntilTest {

    @Test
    fun deadlineAddsHoursToNow() {
        val now = 1_000_000L
        assertEquals(1_000_000L + 3_600_000L, SilentUntilPolicy.deadlineMillis(now, 1))
        assertEquals(1_000_000L + 8 * 3_600_000L, SilentUntilPolicy.deadlineMillis(now, 8))
        assertEquals(1_000_000L + 24 * 3_600_000L, SilentUntilPolicy.deadlineMillis(now, 24))
        assertEquals(now, SilentUntilPolicy.deadlineMillis(now, 0))
    }

    @Test
    fun isActiveWhenDeadlineInFuture() {
        assertTrue(SilentUntilPolicy.isActive(2_000_000L, 1_000_000L))
    }

    @Test
    fun isNotActiveWhenDeadlinePassedOrEqual() {
        assertFalse(SilentUntilPolicy.isActive(999_999L, 1_000_000L))
        assertFalse(SilentUntilPolicy.isActive(1_000_000L, 1_000_000L))
    }

    @Test
    fun optionsCarryExpectedHours() {
        assertEquals(3, SilentUntilOption.entries.size)
        assertEquals(
            listOf(1L, 8L, 24L),
            SilentUntilOption.entries.map { it.hours },
        )
    }
}
