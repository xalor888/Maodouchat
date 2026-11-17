package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiWeeklyReportPolicyTest {

    @Test
    fun `truncate trims surrounding whitespace`() {
        assertEquals("本周要点", AiWeeklyReportPolicy.truncateReport("  本周要点\n"))
    }

    @Test
    fun `truncate caps at max report chars`() {
        assertEquals(20_000, AiWeeklyReportPolicy.truncateReport("x".repeat(30_000)).length)
        assertEquals("x".repeat(100), AiWeeklyReportPolicy.truncateReport("x".repeat(100)))
    }

    @Test
    fun `report constants`() {
        assertEquals(2_000, AiWeeklyReportPolicy.QUERY_LIMIT)
        assertEquals(40, AiWeeklyReportPolicy.MAX_REPORT_MESSAGES)
        assertEquals(20_000, AiWeeklyReportPolicy.MAX_REPORT_CHARS)
        assertEquals("本周群报", AiWeeklyReportPolicy.REPORT_TITLE)
        assertEquals("summary", AiWeeklyReportPolicy.REPORT_MODE)
    }
}
