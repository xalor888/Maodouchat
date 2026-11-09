package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxFlushLimitsTest {

    @Test
    fun `flush handles at most 100 messages per run`() {
        assertEquals(100, OutboxFlushLimits.MAX_MESSAGES_PER_RUN)
    }

    @Test
    fun `stale outbox claim expires after two minutes`() {
        assertEquals(120_000L, OutboxFlushLimits.STALE_CLAIM_MS)
    }
}
