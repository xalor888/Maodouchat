package com.maodouchat.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiWeeklyReportSourceTest {

    private class FixedReportSource(private val report: WeeklyReport?) : AiWeeklyReportSource {
        override suspend fun generate(chatId: String): WeeklyReport? = report
    }

    @Test
    fun `fixed report is returned as-is`() = runBlocking {
        val report = WeeklyReport(chatId = "chat-1", weekStart = 1000L, weekEnd = 2000L, report = "本周小结")
        assertEquals(report, FixedReportSource(report).generate("chat-1"))
    }

    @Test
    fun `null means no report available`() = runBlocking {
        assertNull(FixedReportSource(null).generate("chat-1"))
    }
}
