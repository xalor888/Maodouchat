package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryHistoryUi
import com.maodouchat.core.model.AiSummaryScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSummaryHistoryFilterPolicyTest {

    private fun item(summary: String, scope: AiSummaryScope, cacheKey: String) =
        AiSummaryHistoryUi(
            cacheKey = cacheKey,
            summary = summary,
            scope = scope,
            messageCount = 3,
            createdAt = 0L,
        )

    private val items = listOf(
        item("Weekly digest", AiSummaryScope.SEVEN_DAYS, "k1"),
        item("Unread catch-up", AiSummaryScope.UNREAD, "k2"),
    )

    @Test
    fun `matches summary case-insensitively`() {
        assertTrue(AiSummaryHistoryFilterPolicy.matches(items[0], "DIGEST"))
    }

    @Test
    fun `matches scope name`() {
        assertTrue(AiSummaryHistoryFilterPolicy.matches(items[1], "unread"))
    }

    @Test
    fun `matches cache key`() {
        assertTrue(AiSummaryHistoryFilterPolicy.matches(items[0], "k1"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(AiSummaryHistoryFilterPolicy.matches(items[0], "zzz"))
    }

    @Test
    fun `filter blank returns all`() {
        assertEquals(items, AiSummaryHistoryFilterPolicy.filter(items, "  "))
    }

    @Test
    fun `filter no match returns empty`() {
        assertEquals(
            emptyList<AiSummaryHistoryUi>(),
            AiSummaryHistoryFilterPolicy.filter(items, "zzz"),
        )
    }
}
