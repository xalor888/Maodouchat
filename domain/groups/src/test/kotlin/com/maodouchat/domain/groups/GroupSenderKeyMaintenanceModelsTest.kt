package com.maodouchat.domain.groups

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class GroupSenderKeyMaintenanceModelsTest {

    @Test
    fun `Ready carries status and local key flag`() {
        val outcome = GroupSenderKeyMaintenanceOutcome.Ready(status = null, localHasSenderKey = true)
        assertNull(outcome.status)
        assertTrue(outcome.localHasSenderKey)
    }

    @Test
    fun `Pending carries both errors`() {
        val error = IllegalStateException("boom")
        val outcome = GroupSenderKeyMaintenanceOutcome.Pending(
            status = null, localHasSenderKey = false, error = error, retryQueueError = null,
        )
        assertEquals(error, outcome.error)
        assertNull(outcome.retryQueueError)
    }

    @Test
    fun `Failed carries its error`() {
        val error = IllegalStateException("boom")
        val outcome = GroupSenderKeyMaintenanceOutcome.Failed(error = error)
        assertEquals(error, outcome.error)
        assertNull(outcome.retryQueueError)
    }
}
