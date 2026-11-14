package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class MissedCallRetentionTest {

    @Test
    fun retentionWindowIsTwentyEightDays() {
        assertEquals(28L * 24L * 60L * 60L * 1000L, MISSED_CALL_RETENTION_MS)
        assertEquals(2419200000L, MISSED_CALL_RETENTION_MS)
    }

    @Test
    fun cutoffIsNowMinusRetentionWindow() {
        assertEquals(1697580800000L, missedCallRetentionCutoffMs(1700000000000L))
        assertEquals(0L, missedCallRetentionCutoffMs(MISSED_CALL_RETENTION_MS))
    }
}
