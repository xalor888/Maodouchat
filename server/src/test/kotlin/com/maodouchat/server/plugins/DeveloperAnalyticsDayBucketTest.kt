package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class DeveloperAnalyticsDayBucketTest {

    @Test
    fun `unix day start floors to UTC midnight matching SQL CAST ts over 86400000`() {
        val dayMs = 86_400_000L
        val midDay = 1_700_000_000_000L
        val expectedBucket = midDay / dayMs
        val start = unixDayStartMs(midDay, dayMs)
        assertEquals(expectedBucket, start / dayMs)
        assertEquals(0L, start % dayMs)

        val unnormalizedLookup = midDay / dayMs
        val normalizedLookup = start / dayMs
        assertEquals(unnormalizedLookup, normalizedLookup)
    }

    @Test
    fun `offset from now without floor can miss the SQL day bucket`() {
        val dayMs = 86_400_000L
        val now = 1_700_123_456_789L
        val unnormalized = now - 0 * dayMs
        assertEquals(now, unnormalized)
        assertTrue(unnormalized % dayMs != 0L)
        val floored = unixDayStartMs(unnormalized, dayMs)
        assertEquals(now / dayMs, floored / dayMs)
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}
