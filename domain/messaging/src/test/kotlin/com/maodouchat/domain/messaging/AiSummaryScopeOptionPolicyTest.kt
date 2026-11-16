package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSummaryScopeOptionPolicyTest {

    @Test
    fun `non-search scopes are always enabled`() {
        for (scope in listOf(
            AiSummaryScope.RECENT, AiSummaryScope.TODAY, AiSummaryScope.SEVEN_DAYS,
            AiSummaryScope.THIRTY_DAYS, AiSummaryScope.UNREAD
        )) {
            assertTrue(AiSummaryScopeOptionPolicy.isEnabled(scope, 0))
        }
    }

    @Test
    fun `search results needs at least one result`() {
        assertFalse(AiSummaryScopeOptionPolicy.isEnabled(AiSummaryScope.SEARCH_RESULTS, 0))
        assertTrue(AiSummaryScopeOptionPolicy.isEnabled(AiSummaryScope.SEARCH_RESULTS, 1))
    }

    @Test
    fun `static scopes map to static kinds`() {
        assertEquals(
            AiSummaryScopeDescriptionKind.RECENT,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.RECENT, 0)
        )
        assertEquals(
            AiSummaryScopeDescriptionKind.TODAY,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.TODAY, 0)
        )
        assertEquals(
            AiSummaryScopeDescriptionKind.WEEK,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.SEVEN_DAYS, 0)
        )
        assertEquals(
            AiSummaryScopeDescriptionKind.MONTH,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.THIRTY_DAYS, 0)
        )
    }

    @Test
    fun `search results description depends on count`() {
        assertEquals(
            AiSummaryScopeDescriptionKind.SEARCH_EMPTY,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.SEARCH_RESULTS, 0)
        )
        assertEquals(
            AiSummaryScopeDescriptionKind.SEARCH_COUNT,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.SEARCH_RESULTS, 5)
        )
    }

    @Test
    fun `unread maps to empty description kind`() {
        // 对话框的范围列表不含 UNREAD，该分支仅为 when 穷尽而存在。
        assertEquals(
            AiSummaryScopeDescriptionKind.UNREAD_EMPTY,
            AiSummaryScopeOptionPolicy.descriptionKind(AiSummaryScope.UNREAD, 0)
        )
    }
}
