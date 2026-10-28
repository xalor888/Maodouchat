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
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
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
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(NoRecipientDevicesException()))
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

    @Test
    fun englishNotReadyTextIsConflict() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "Attachment not ready, upload pending",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
    }

    @Test
    fun uploadKeywordVariantIsConflict() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "attachment upload in progress",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun attachmentAloneIsNotConflict() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "attachment missing",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun serverCodeMatchedCaseInsensitivelyButExactly() {
        val lower = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverCode = "message_already_accepted",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(lower))
        val suffixed = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverCode = "MESSAGE_ALREADY_ACCEPTED_EXTRA",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(suffixed))
    }

    @Test
    fun timeoutKindIsRetryableButNeitherConflictNorDuplicate() {
        val err = ApiException(ApiFailureKind.TIMEOUT, serverMessage = "slow")
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
    }

    @Test
    fun nullErrorIsNeitherConflictNorDuplicate() {
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(null))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(null))
    }

    @Test
    fun nonApiErrorIsNeitherConflictNorDuplicate() {
        val err = IOException("eof")
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
    }

    @Test
    fun non409NotReadyTextIsNotConflictButStillRetryableVia5xx() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 500,
            serverMessage = "附件尚未上传完成",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun mixedCaseWhitespaceAcceptedCodeIsAcceptedNotRetryable() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverCode = "  Message_Already_Accepted ",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun deepChainInitializationMessageIsTransient() {
        val err = RuntimeException(
            "l1",
            RuntimeException(
                "l2",
                RuntimeException(
                    "l3",
                    RuntimeException("l4", RuntimeException("protocol initialization")),
                ),
            ),
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }
    @Test
    fun networkKindNotReadyTextIsNotConflict() {
        val err = ApiException(
            ApiFailureKind.NETWORK,
            statusCode = 409,
            serverMessage = "附件尚未上传完成",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(err))
    }

    @Test
    fun bareChineseNotReadyPrefixIsConflict() {
        val err = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "附件尚未",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
    }

    @Test
    fun englishKeywordNeedsAttachmentCompanion() {
        val uploadOnly = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "upload pending",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(uploadOnly))
        val keywordOnly = ApiException(
            ApiFailureKind.HTTP,
            statusCode = 409,
            serverMessage = "attachment processing",
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(keywordOnly))
    }

    @Test
    fun acceptedCodeNeedsHttpKindAndNonBlankCode() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.NETWORK, serverCode = "MESSAGE_ALREADY_ACCEPTED")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.HTTP, serverCode = null)
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.HTTP, serverCode = "   ")
            )
        )
    }

    @Test
    fun nullStatusAndUnexpectedKindAreNotRetryable() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(ApiException(ApiFailureKind.HTTP))
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.UNEXPECTED, serverMessage = "weird")
            )
        )
    }

}
