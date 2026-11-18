package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiProfileRepositoryModelsTest {

    @Test
    fun `profile row keeps fields and nullable narrative`() {
        val row = ProfileRow("chat-1", "{}", null, 42L)
        assertEquals("chat-1", row.chatId)
        assertEquals("{}", row.statsJson)
        assertNull(row.narrative)
        assertEquals(42L, row.updatedAt)
    }

    @Test
    fun `archive row keeps fields`() {
        val row = ArchiveRow("chat-1", 9, "idle", 77L)
        assertEquals(9, row.score)
        assertEquals("idle", row.reason)
        assertEquals(77L, row.suggestedAt)
    }

    @Test
    fun `weekly report row keeps window and nullable model`() {
        val row = WeeklyReportRow("chat-1", 100L, 200L, "report", null, 300L)
        assertEquals(100L, row.weekStart)
        assertEquals(200L, row.weekEnd)
        assertEquals("report", row.report)
        assertNull(row.model)
        assertEquals(300L, row.createdAt)
    }
}
