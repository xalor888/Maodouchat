package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import kotlin.test.Test
import kotlin.test.assertEquals

class AiSummaryCacheKeyTest {

    @Test
    fun legalStylesPassThrough() {
        for (style in listOf("brief", "detailed", "decisions", "tasks", "timeline", "risks")) {
            assertEquals(style, normalizeAiSummaryStyle(style, AiSummaryScope.RECENT), style)
        }
    }

    @Test
    fun styleIsTrimmedAndLowercased() {
        assertEquals("brief", normalizeAiSummaryStyle(" Brief ", AiSummaryScope.TODAY))
        assertEquals("detailed", normalizeAiSummaryStyle("DETAILED", AiSummaryScope.TODAY))
    }

    @Test
    fun illegalStyleFallsBackToBriefForRecent() {
        assertEquals("brief", normalizeAiSummaryStyle("bogus", AiSummaryScope.RECENT))
        assertEquals("brief", normalizeAiSummaryStyle("", AiSummaryScope.RECENT))
    }

    @Test
    fun illegalStyleFallsBackToDetailedForOtherScopes() {
        for (scope in listOf(AiSummaryScope.TODAY, AiSummaryScope.UNREAD, AiSummaryScope.SEARCH_RESULTS)) {
            assertEquals("detailed", normalizeAiSummaryStyle("bogus", scope), scope.name)
        }
    }

    @Test
    fun manualKeyFormat() {
        assertEquals(
            "manual:c1:RECENT:brief:m1:m2:5",
            buildAiSummaryCacheKey("c1", AiSummaryScope.RECENT, "brief", "m1", "m2", 5),
        )
    }

    @Test
    fun scopeRoundTripsThroughKey() {
        for (scope in AiSummaryScope.entries) {
            val key = buildAiSummaryCacheKey("c1", scope, "brief", "m1", "m2", 5)
            assertEquals(scope, summaryScopeFromCacheKey(key), scope.name)
        }
    }

    @Test
    fun nonManualKeyIsUnread() {
        assertEquals(AiSummaryScope.UNREAD, summaryScopeFromCacheKey("c1:m1:m2:5"))
        assertEquals(AiSummaryScope.UNREAD, summaryScopeFromCacheKey(""))
    }

    @Test
    fun unknownScopeNameIsRecent() {
        assertEquals(AiSummaryScope.RECENT, summaryScopeFromCacheKey("manual:c1:BOGUS:brief:m1:m2:5"))
    }

    @Test
    fun shortKeyIsRecent() {
        assertEquals(AiSummaryScope.RECENT, summaryScopeFromCacheKey("manual:c1"))
    }
}
