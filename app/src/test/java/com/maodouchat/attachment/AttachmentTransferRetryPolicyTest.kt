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
}
