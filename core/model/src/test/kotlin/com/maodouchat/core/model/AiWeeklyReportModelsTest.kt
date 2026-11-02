package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AiWeeklyReportModelsTest {

    @Test
    fun `week range spans exactly seven days`() {
        val (start, end) = AiWeeklyReportWeekRange.currentWeekRange()
        assertEquals(7L * 24L * 60L * 60L * 1000L, end - start)
    }

    @Test
    fun `week range starts on monday at midnight`() {
        val wednesday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (start, _) = AiWeeklyReportWeekRange.currentWeekRange(wednesday)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(2, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `weekly report defaults model to null`() {
        val report = WeeklyReport("chat-1", 1000L, 2000L, "summary")
        assertNull(report.model)
        assertTrue(report.createdAt > 0L)
        assertEquals(report, report.copy())
    }
}
