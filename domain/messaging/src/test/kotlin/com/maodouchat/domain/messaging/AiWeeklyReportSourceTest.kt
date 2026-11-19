package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.WeeklyReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

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
