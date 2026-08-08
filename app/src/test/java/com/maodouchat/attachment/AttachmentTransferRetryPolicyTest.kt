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
            AttachmentTransferFinalizer.isRetryable(
                ApiException(ApiFailureKind.NETWORK, serverMessage = "down")
            )
        )
        assertTrue(
            AttachmentTransferFinalizer.isRetryable(
                ApiException(ApiFailureKind.TIMEOUT, serverMessage = "slow")
            )
        )
        assertTrue(AttachmentTransferFinalizer.isRetryable(IOException("eof")))
    }

    @Test
    fun server5xxIsRetryable() {
        assertTrue(
            AttachmentTransferFinalizer.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 503, serverMessage = "busy")
            )
        )
    }

    @Test
    fun client4xxIsDefinitive() {
        assertFalse(
            AttachmentTransferFinalizer.isRetryable(
                ApiException(ApiFailureKind.HTTP, statusCode = 403, serverMessage = "forbidden")
            )
        )
        assertFalse(
            AttachmentTransferFinalizer.isRetryable(
                IllegalStateException("attachment_transfer_invalid")
            )
        )
    }
}
