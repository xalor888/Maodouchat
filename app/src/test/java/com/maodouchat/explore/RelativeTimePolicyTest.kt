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

    @Test
    fun customPastWindowWidensJustNowRange() {
        val now = 1_000_000L
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now - 119_999L, now, pastWindowMs = 120_000L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 119_999L, now))
    }

    @Test
    fun customPastWindowNarrowsJustNowRange() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 31_000L, now, pastWindowMs = 30_000L))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now - 31_000L, now))
    }

    @Test
    fun customFutureGraceWidensSkewTolerance() {
        val now = 1_000_000L
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now + 240_000L, now, futureGraceMs = 300_000L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 240_000L, now))
    }

    @Test
    fun zeroCustomWindowsCollapseToEmptyRange() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now, now, pastWindowMs = 0L, futureGraceMs = 0L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 1L, now, pastWindowMs = 0L, futureGraceMs = 0L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 1L, now, pastWindowMs = 0L, futureGraceMs = 0L))
    }

    @Test
    fun negativeTimestampIsNeverJustNow() {
        assertFalse(RelativeTimePolicy.shouldUseJustNow(-1L, 1_000_000L))
        assertFalse(
            RelativeTimePolicy.shouldUseJustNow(
                -1L, 1_000_000L, pastWindowMs = Long.MAX_VALUE, futureGraceMs = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun defaultWindowConstantsAreStable() {
        assertTrue(RelativeTimePolicy.PAST_JUST_NOW_MS == 60_000L)
        assertTrue(RelativeTimePolicy.FUTURE_GRACE_MS == 120_000L)
    }

    @Test
    fun pastWindowUpperBoundaryIsExclusive() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 60_000L, now))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now - 59_999L, now))
    }

    @Test
    fun futureGraceUpperBoundaryIsExclusive() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 120_000L, now))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now + 119_999L, now))
    }

    @Test
    fun farFutureTimestampBeyondDefaultGraceIsNotJustNow() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 10_000_000L, now))
        assertTrue(
            RelativeTimePolicy.shouldUseJustNow(now + 10_000_000L, now, futureGraceMs = Long.MAX_VALUE)
        )
    }

    @Test
    fun distantPastBeyondDefaultWindowIsNotJustNow() {
        val now = 10_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 3_600_000L, now))
        assertTrue(
            RelativeTimePolicy.shouldUseJustNow(now - 3_600_000L, now, pastWindowMs = Long.MAX_VALUE)
        )
    }

    @Test
    fun narrowedFutureGraceRejectsModerateSkew() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 31_000L, now, futureGraceMs = 30_000L))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now + 31_000L, now))
    }

    @Test
    fun zeroNowWithFutureTimestampUsesGrace() {
        assertTrue(RelativeTimePolicy.shouldUseJustNow(60_000L, 0L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(120_000L, 0L))
    }

    @Test
    fun exactNowWithZeroPastWindowIsNotJustNow() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now, now, pastWindowMs = 0L))
    }

    @Test
    fun negativeWindowsCollapseToEmptyRange() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now, now, pastWindowMs = -1L, futureGraceMs = -1L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 1L, now, pastWindowMs = -1L, futureGraceMs = -1L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now + 1L, now, pastWindowMs = -1L, futureGraceMs = -1L))
    }

    @Test
    fun tinyCustomPastWindowPinsExclusiveBound() {
        val now = 1_000_000L
        assertFalse(RelativeTimePolicy.shouldUseJustNow(now - 1L, now, pastWindowMs = 1L))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(now - 1L, now, pastWindowMs = 2L))
    }
}
