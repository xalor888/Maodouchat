package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDetailUiDataModelsTest {

    @Test
    fun `read count holds read and total`() {
        assertEquals(ReadCountUi(3, 5), ReadCountUi(read = 3, total = 5))
    }

    @Test
    fun `read receipt defaults to unknown presence`() {
        val receipt = ReadReceiptUi(userId = "u1", name = "Ann")
        assertEquals(null, receipt.avatar)
        assertEquals(null, receipt.readAt)
        assertEquals(null, receipt.playedAt)
        assertEquals(false, receipt.isOnline)
    }

    @Test
    fun `summary history carries its scope`() {
        val item = AiSummaryHistoryUi(
            cacheKey = "k",
            summary = "s",
            scope = AiSummaryScope.TODAY,
            messageCount = 12,
            createdAt = 1000L
        )
        assertEquals(AiSummaryScope.TODAY, item.scope)
        assertEquals(12, item.messageCount)
    }

    @Test
    fun `ai operation defaults to no retry timing`() {
        val op = AiOperationUi(id = "o1", type = "t", state = "s", attempts = 1, lastErrorCode = null)
        assertEquals(null, op.nextRetryAtMs)
        assertEquals(null, op.retryAfterSeconds)
    }
}
