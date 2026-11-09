package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class InboxSyncLimitsTest {

    @Test
    fun `pull page size is 200`() {
        assertEquals(200, InboxSyncLimits.PULL_LIMIT)
    }

    @Test
    fun `ack batch size is 200`() {
        assertEquals(200, InboxSyncLimits.ACK_LIMIT)
    }

    @Test
    fun `pull loop is capped at 20 pages`() {
        assertEquals(20, InboxSyncLimits.MAX_PULL_PAGES)
    }

    @Test
    fun `stale processing claim expires after two minutes`() {
        assertEquals(120_000L, InboxSyncLimits.STALE_PROCESSING_MS)
    }

    @Test
    fun `poll interval is thirty seconds`() {
        assertEquals(30_000L, InboxSyncLimits.POLL_INTERVAL_MS)
    }
}
