package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiSummaryScopeKindTest {

    @Test
    fun `all six scopes map to their kinds`() {
        assertEquals(AiSummaryScopeKind.RECENT, aiSummaryScopeKind(AiSummaryScope.RECENT))
        assertEquals(AiSummaryScopeKind.TODAY, aiSummaryScopeKind(AiSummaryScope.TODAY))
        assertEquals(AiSummaryScopeKind.SEVEN_DAYS, aiSummaryScopeKind(AiSummaryScope.SEVEN_DAYS))
        assertEquals(AiSummaryScopeKind.THIRTY_DAYS, aiSummaryScopeKind(AiSummaryScope.THIRTY_DAYS))
        assertEquals(AiSummaryScopeKind.SEARCH_RESULTS, aiSummaryScopeKind(AiSummaryScope.SEARCH_RESULTS))
        assertEquals(AiSummaryScopeKind.UNREAD, aiSummaryScopeKind(AiSummaryScope.UNREAD))
    }
}
