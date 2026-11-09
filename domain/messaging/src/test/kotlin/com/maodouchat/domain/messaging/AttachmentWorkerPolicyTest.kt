package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentWorkerPolicyTest {

    @Test
    fun `completed upload requires id, size and full bytes`() {
        assertTrue(AttachmentWorkerPolicy.hasCompletedUpload("att_complete", 1_024L, 1_024L))
        assertFalse(AttachmentWorkerPolicy.hasCompletedUpload(null, 1_024L, 1_024L))
        assertFalse(AttachmentWorkerPolicy.hasCompletedUpload("", 1_024L, 1_024L))
        assertFalse(AttachmentWorkerPolicy.hasCompletedUpload("att", 0L, 0L))
        assertFalse(AttachmentWorkerPolicy.hasCompletedUpload("att_partial", 1_024L, 512L))
    }

    @Test
    fun `retry without upload only for failed or paused completed transfers`() {
        assertTrue(AttachmentWorkerPolicy.canRetryWithoutUpload("FAILED", true))
        assertTrue(AttachmentWorkerPolicy.canRetryWithoutUpload("PAUSED", true))
        assertFalse(AttachmentWorkerPolicy.canRetryWithoutUpload("FAILED", false))
        assertFalse(AttachmentWorkerPolicy.canRetryWithoutUpload("PAUSED", false))
        assertFalse(AttachmentWorkerPolicy.canRetryWithoutUpload("QUEUED", true))
        assertFalse(AttachmentWorkerPolicy.canRetryWithoutUpload("READY", true))
    }

    @Test
    fun `ready transfer finalizes only when upload completed`() {
        assertEquals(AttachmentWorkerAction.FINALIZE, AttachmentWorkerPolicy.nextAction("READY", true))
        assertEquals(AttachmentWorkerAction.FAIL, AttachmentWorkerPolicy.nextAction("READY", false))
    }

    @Test
    fun `failed transfer promotes when upload completed, uploads otherwise`() {
        assertEquals(AttachmentWorkerAction.PROMOTE_AND_FINALIZE, AttachmentWorkerPolicy.nextAction("FAILED", true))
        assertEquals(AttachmentWorkerAction.UPLOAD, AttachmentWorkerPolicy.nextAction("FAILED", false))
    }

    @Test
    fun `preparing paused and sending transfers stop the worker`() {
        listOf("PREPARING", "PAUSED", "SENDING").forEach { state ->
            assertEquals(AttachmentWorkerAction.STOP, AttachmentWorkerPolicy.nextAction(state, true))
            assertEquals(AttachmentWorkerAction.STOP, AttachmentWorkerPolicy.nextAction(state, false))
        }
    }

    @Test
    fun `queued and uploading transfers upload`() {
        assertEquals(AttachmentWorkerAction.UPLOAD, AttachmentWorkerPolicy.nextAction("QUEUED", false))
        assertEquals(AttachmentWorkerAction.UPLOAD, AttachmentWorkerPolicy.nextAction("UPLOADING", false))
        assertEquals(AttachmentWorkerAction.UPLOAD, AttachmentWorkerPolicy.nextAction("QUEUED", true))
    }

    @Test
    fun `unknown state fails closed`() {
        assertEquals(AttachmentWorkerAction.FAIL, AttachmentWorkerPolicy.nextAction("CORRUPTED", true))
        assertFalse(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("CORRUPTED", true))
    }

    @Test
    fun `process-death reschedule only for pending or completed transfers`() {
        assertTrue(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("QUEUED", false))
        assertTrue(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("UPLOADING", false))
        assertTrue(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("READY", true))
        assertTrue(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("FAILED", true))
        assertFalse(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("READY", false))
        assertFalse(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath("FAILED", false))
        listOf("PREPARING", "PAUSED", "SENDING").forEach { state ->
            assertFalse(AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath(state, true))
        }
    }
}
