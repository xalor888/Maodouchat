package com.maodouchat.attachment

import com.maodouchat.crypto.NoRecipientDevicesException
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTransferRetryPolicyTest {

    @Test
    fun networkAndTimeoutAreRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.NETWORK, serverMessage = "down")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.TIMEOUT, serverMessage = "slow")
            )
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(IOException("eof")))
    }

    @Test
    fun server5xxIsRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 503, serverMessage = "busy")
            )
        )
    }

    @Test
    fun client4xxIsDefinitive() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 403, serverMessage = "forbidden")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                IllegalStateException("attachment_transfer_invalid")
            )
        )
    }

    @Test
    fun missingPeerPrekeysAndSignalInitAreRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                NoRecipientDevicesException()
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                IllegalStateException("signal_initialization_failed")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                RuntimeException("wrap", NoRecipientDevicesException())
            )
        )
    }

    @Test
    fun rateLimited429IsRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 429, serverMessage = "slow down")
            )
        )
    }

    @Test
    fun attachmentNotReadyConflict409IsRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "附件尚未上传完成")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "attachment upload not ready")
            )
        )
    }

    @Test
    fun unrelated409AndSub500AreNotRetryable() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "version conflict")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 499, serverMessage = "client closed")
            )
        )
    }

    @Test
    fun cryptoTransientMessagesAreRetryable() {
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(RuntimeException("attachment not ready")))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                RuntimeException("wrap", RuntimeException("protocol initialization failed"))
            )
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(RuntimeException("attachment corrupt")))
    }

    @Test
    fun causeChainDeeperThanSixIsNotTreatedAsTransient() {
        fun nested(depth: Int, leaf: Throwable): Throwable =
            if (depth == 0) leaf else RuntimeException("level$depth", nested(depth - 1, leaf))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(nested(5, NoRecipientDevicesException()))
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(nested(6, NoRecipientDevicesException()))
        )
    }

    @Test
    fun http400And404AreDefinitive() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 400, serverMessage = "bad request")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 404, serverMessage = "not found")
            )
        )
    }

    @Test
    fun timeoutKindIgnoresStatusCode() {
        val err = ApiException(
            ApiFailureKind.TIMEOUT,
            statusCode = 403,
            serverMessage = "slow",
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(err))
        assertFalse(AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(err))
    }

    @Test
    fun ioExceptionSubclassIsRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                SocketTimeoutException("read timed out")
            )
        )
    }

    @Test
    fun wrappedIOExceptionIsNotRetryable() {
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                RuntimeException("wrap", IOException("eof"))
            )
        )
    }

    @Test
    fun messageChainAtDepthFiveIsTransientButSixIsNot() {
        fun nested(depth: Int, leaf: Throwable): Throwable =
            if (depth == 0) leaf else RuntimeException("level$depth", nested(depth - 1, leaf))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                nested(5, RuntimeException("protocol initialization"))
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(
                nested(6, RuntimeException("protocol initialization"))
            )
        )
    }

    @Test
    fun alreadyAcceptedDuplicateMatchesTrimmedCaseInsensitiveCode() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.HTTP, serverCode = "MESSAGE_ALREADY_ACCEPTED")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.HTTP, serverCode = " message_already_accepted ")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.HTTP, serverCode = "OTHER")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(
                ApiException(ApiFailureKind.NETWORK, serverCode = "MESSAGE_ALREADY_ACCEPTED")
            )
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(RuntimeException("x")))
        assertFalse(AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(null))
    }

    @Test
    fun notReadyConflictEnglishAndBareChineseVariants() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "attachment upload pending")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "attachment ready")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "尚未上传完成")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409)
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.NETWORK, statusCode = 409, serverMessage = "attachment not ready")
            )
        )
    }

    @Test
    fun httpStatusBoundariesForRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 500, serverMessage = "err")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 501, serverMessage = "err")
            )
        )
        assertFalse(AttachmentSendAfterUploadPolicy.isRetryable(ApiException(ApiFailureKind.HTTP)))
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                ApiException(ApiFailureKind.NETWORK, statusCode = 403, serverMessage = "down")
            )
        )
    }

    @Test
    fun transientCryptoMessageMatchingIsCaseSensitive() {
        assertFalse(AttachmentSendAfterUploadPolicy.isTransientCryptoError(RuntimeException("NOT READY")))
        assertFalse(AttachmentSendAfterUploadPolicy.isTransientCryptoError(RuntimeException("Protocol Initialization")))
        assertTrue(AttachmentSendAfterUploadPolicy.isTransientCryptoError(RuntimeException("x protocol initialization y")))
    }

    @Test
    fun notReadyConflictMatchingIsCaseInsensitive() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "ATTACHMENT NOT READY")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "Attachment Upload")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "attachment")
            )
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "")
            )
        )
    }

}
