package com.maodouchat.domain.messaging

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexStalePolicyTest {
    @Test
    fun bothZeroSkipsWithoutCheckingMismatch() = runTest {
        var calls = 0
        assertFalse(SearchIndexStalePolicy.shouldRefreshIndex(0, 0) { calls++; true })
        assertEquals(0, calls)
    }

    @Test
    fun emptyIndexWithMessagesRefreshes() = runTest {
        assertTrue(SearchIndexStalePolicy.shouldRefreshIndex(0, 100) { error("must not be called") })
    }

    @Test
    fun driftWithinThresholdWithoutMismatchSkips() = runTest {
        var calls = 0
        assertFalse(SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { calls++; false })
        assertEquals(1, calls)
    }

    @Test
    fun driftWithinThresholdWithMismatchRefreshes() = runTest {
        assertTrue(SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { true })
    }

    @Test
    fun driftOverThresholdRefreshesWithoutCheckingMismatch() = runTest {
        var calls = 0
        assertTrue(SearchIndexStalePolicy.shouldRefreshIndex(100, 200) { calls++; false })
        assertEquals(0, calls)
    }

    @Test
    fun driftExactlyAtThresholdRefreshes() = runTest {
        // msg=1000 -> 阈值 max(100, 50)=100；drift=100 不满足严格小于，触发重建。
        assertTrue(SearchIndexStalePolicy.shouldRefreshIndex(900, 1000) { false })
    }

    @Test
    fun smallLibraryUsesMinDriftFloor() = runTest {
        // msg=100 -> 阈值 max(10, 50)=50；drift=40 在阈值内，跳过。
        assertFalse(SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { false })
    }

    @Test
    fun zeroMessagesWithDocumentsRefreshes() = runTest {
        assertTrue(SearchIndexStalePolicy.shouldRefreshIndex(50, 0) { false })
    }
}
