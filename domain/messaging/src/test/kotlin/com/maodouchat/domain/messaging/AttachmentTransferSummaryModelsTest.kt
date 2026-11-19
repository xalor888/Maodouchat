package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class AttachmentTransferSummaryModelsTest {

    @Test
    fun `empty summary has no failures or activity`() {
        val summary = Summary()
        assertEquals(0, summary.active)
        assertEquals(0, summary.failed)
        assertFalse(summary.hasFailures)
        assertFalse(summary.hasActivity)
    }

    @Test
    fun `summary derives failures and activity`() {
        assertTrue(Summary(failed = 1).hasFailures)
        assertTrue(Summary(active = 1).hasActivity)
        assertTrue(Summary(queued = 2).hasActivity)
        assertFalse(Summary(completed = 3).hasActivity)
    }
}
