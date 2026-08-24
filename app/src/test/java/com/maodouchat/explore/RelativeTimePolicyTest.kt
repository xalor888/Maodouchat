package com.maodouchat.explore

import com.maodouchat.ui.screen.explore.RelativeTimePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelativeTimePolicyTest {

    @Test
    fun justNowForPastUnderOneMinute() {
        val now = 1_000_000L
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now, now))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now - 59_000L, now))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 60_000L, now))
    }

    @Test
    fun justNowForSlightlyFutureClockSkew() {
        val now = 1_000_000L
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now + 1_000L, now))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now + 119_000L, now))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 120_000L, now))
    }

    @Test
    fun zeroTimestampIsNotJustNow() {
        assertFalse(RelativeTimePolicy.shouldUseJustNow(0L, 1_000_000L))
    }
}
