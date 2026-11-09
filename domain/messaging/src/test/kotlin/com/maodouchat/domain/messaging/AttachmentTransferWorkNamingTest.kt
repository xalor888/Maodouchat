package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentTransferWorkNamingTest {

    @Test
    fun `plain ids keep work name unchanged`() {
        assertEquals(
            "attachment_transfer_user-1_msg_42",
            AttachmentTransferWorkNaming.workName("user-1", "msg_42"),
        )
    }

    @Test
    fun `unsafe chars in ids are replaced with underscore`() {
        assertEquals(
            "attachment_transfer_user_1_x_msg_2_3",
            AttachmentTransferWorkNaming.workName("user:1/x", "msg.2 3"),
        )
    }

    @Test
    fun `allowed chars survive sanitization`() {
        assertEquals(
            "attachment_transfer_AZaz09-__xY_9",
            AttachmentTransferWorkNaming.workName("AZaz09-_", "xY_9"),
        )
    }

    @Test
    fun `naming constants keep wire values`() {
        assertEquals("attachment_transfer_", AttachmentTransferWorkNaming.WORK_PREFIX)
        assertEquals("attachment_transfer", AttachmentTransferWorkNaming.TAG)
        assertEquals("message_id", AttachmentTransferWorkNaming.KEY_MESSAGE_ID)
        assertEquals("owner_user_id", AttachmentTransferWorkNaming.KEY_OWNER_USER_ID)
    }

    @Test
    fun `worker input resolves when both ids present`() {
        assertEquals(
            "msg_1" to "user_1",
            AttachmentTransferWorkNaming.resolveWorkerInput("msg_1", "user_1"),
        )
    }

    @Test
    fun `worker input fails when message id missing or blank`() {
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput(null, "user_1"))
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput("", "user_1"))
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput("   ", "user_1"))
    }

    @Test
    fun `worker input fails when owner missing or blank`() {
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput("msg_1", null))
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput("msg_1", ""))
        assertNull(AttachmentTransferWorkNaming.resolveWorkerInput("msg_1", "   "))
    }
}
