package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxPullContinuationTest {

    @Test
    fun `a completed batch with more pages continues`() {
        assertEquals(
            InboxPullContinuation.CONTINUE,
            decideInboxPullContinuation(batchCompleted = true, hasMore = true),
        )
    }

    @Test
    fun `an incomplete batch stops even with more pages`() {
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = false, hasMore = true),
        )
    }

    @Test
    fun `a completed batch with no more pages stops`() {
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = true, hasMore = false),
        )
    }

    @Test
    fun `an incomplete batch with no more pages stops`() {
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = false, hasMore = false),
        )
    }

    @Test
    fun `a short acknowledgement batch drains the queue`() {
        assertFalse(shouldFetchMoreAcknowledgements(fetchedCount = 0, limit = 200))
        assertFalse(shouldFetchMoreAcknowledgements(fetchedCount = 199, limit = 200))
    }

    @Test
    fun `a full acknowledgement batch fetches again`() {
        assertTrue(shouldFetchMoreAcknowledgements(fetchedCount = 200, limit = 200))
    }
}
