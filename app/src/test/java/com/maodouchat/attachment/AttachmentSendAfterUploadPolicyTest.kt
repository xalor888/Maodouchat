package com.maodouchat.attachment

import com.maodouchat.crypto.NoRecipientDevicesException
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentSendAfterUploadPolicyTest {

    @Test
    fun attachmentNotReady409IsRetryableNotDuplicate() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "附件尚未上传完成，请稍后重试",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
        assertTrue(AttachmentTransferFinalizer.isRetryable(err))
    }

    @Test
    fun stableAlreadyAcceptedCodeIsAcceptedNotRetryable() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "already accepted",
            serverCode = "  message_already_accepted  ",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertTrue(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun messageIdConflictIsDefinitiveFailure() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "message id belongs to different content",
            serverCode = "MESSAGE_ID_CONFLICT",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun generic409AndLocalizedMessageIdTextAreNotAccepted() {
        val generic = ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "conflict")
        val localized = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "消息 ID 已存在",
        )

        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(generic))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(localized))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(generic))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(localized))
    }

    @Test
    fun missingPeerPrekeysAreRetryable() {
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(NoRecipientDevicesException()))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                RuntimeException("wrap", NoRecipientDevicesException()),
            ),
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                IllegalStateException("signal_initialization_failed"),
            ),
        )
        assertTrue(AttachmentTransferFinalizer.isRetryable(NoRecipientDevicesException()))
    }

    @Test
    fun networkAnd5xxStayRetryable() {
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(IOException("eof")))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 503, serverMessage = "busy"),
            ),
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 429, serverMessage = "slow"),
            ),
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 403, serverMessage = "forbidden"),
            ),
        )
    }
}
