package com.maodouchat.domain.messaging

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentSendAfterUploadPolicyTest {

    @Test
    fun attachmentSendAfterUploadRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                NoRecipientDevicesException(),
            ),
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(IOException()))
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(RuntimeException("boom")),
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(null),
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(null),
        )
    }
}
