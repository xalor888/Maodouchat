package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentTransferTimingTest {

    @Test
    fun `orphan grace is 24 hours`() {
        assertEquals(24L * 60L * 60L * 1_000L, AttachmentTransferTiming.ORPHAN_GRACE_MS)
    }

    @Test
    fun `stale sending window is 2 minutes`() {
        assertEquals(2L * 60L * 1_000L, AttachmentTransferTiming.STALE_SENDING_MS)
    }
}
