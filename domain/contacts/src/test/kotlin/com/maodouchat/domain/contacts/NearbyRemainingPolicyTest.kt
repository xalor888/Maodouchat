package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRemainingPolicyTest {

    @Test
    fun `not sharing maps to Disabled`() {
        assertTrue(NearbyRemainingPolicy.nearbyRemainingHint(false, 120_000L) is NearbyRemainingHint.Disabled)
        assertTrue(NearbyRemainingPolicy.nearbyRemainingHint(false, 0L) is NearbyRemainingHint.Disabled)
    }

    @Test
    fun `zero negative and sub minute remainders map to Soon`() {
        listOf(0L, -1L, 1L, 59_999L).forEach { ms ->
            assertTrue("$ms 应为 Soon", NearbyRemainingPolicy.nearbyRemainingHint(true, ms) is NearbyRemainingHint.Soon)
        }
    }

    @Test
    fun `exactly one minute maps to one minute`() {
        val hint = NearbyRemainingPolicy.nearbyRemainingHint(true, 60_000L)
        assertTrue(hint is NearbyRemainingHint.Minutes)
        assertEquals(1, (hint as NearbyRemainingHint.Minutes).minutes)
    }

    @Test
    fun `partial minutes round up`() {
        assertEquals(
            2,
            (NearbyRemainingPolicy.nearbyRemainingHint(true, 60_001L) as NearbyRemainingHint.Minutes).minutes
        )
        assertEquals(
            2,
            (NearbyRemainingPolicy.nearbyRemainingHint(true, 119_999L) as NearbyRemainingHint.Minutes).minutes
        )
        assertEquals(
            2,
            (NearbyRemainingPolicy.nearbyRemainingHint(true, 120_000L) as NearbyRemainingHint.Minutes).minutes
        )
    }
}
