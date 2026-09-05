package com.maodouchat.attachment

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import java.io.IOException
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
                com.maodouchat.crypto.NoRecipientDevicesException()
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                IllegalStateException("signal_initialization_failed")
            )
        )
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                RuntimeException("wrap", com.maodouchat.crypto.NoRecipientDevicesException())
            )
        )
    }
}
