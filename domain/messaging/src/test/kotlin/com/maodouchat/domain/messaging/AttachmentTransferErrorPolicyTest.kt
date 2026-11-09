package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.AttachmentTransferDisposition
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentTransferErrorPolicyTest {

    @Test
    fun `api error code is kind plus status`() {
        assertEquals(
            "HTTP_500",
            AttachmentTransferErrorPolicy.transferErrorCode(
                ApiException(ApiFailureKind.HTTP, statusCode = 500)
            ),
        )
        assertEquals(
            "NETWORK_0",
            AttachmentTransferErrorPolicy.transferErrorCode(
                ApiException(ApiFailureKind.NETWORK)
            ),
        )
    }

    @Test
    fun `plain error code is class simple name`() {
        assertEquals(
            "RuntimeException",
            AttachmentTransferErrorPolicy.transferErrorCode(RuntimeException("boom")),
        )
        assertEquals(
            "IOException",
            AttachmentTransferErrorPolicy.transferErrorCode(IOException("eof")),
        )
    }

    @Test
    fun `anonymous error code falls back to UNKNOWN`() {
        assertEquals(
            "UNKNOWN",
            AttachmentTransferErrorPolicy.transferErrorCode(object : Exception() {}),
        )
    }

    @Test
    fun `retry-after makes api error retryable`() {
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.HTTP, statusCode = 404, retryAfterSeconds = 30)
            ),
        )
    }

    @Test
    fun `network and timeout kinds are retryable`() {
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.NETWORK)
            ),
        )
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.TIMEOUT)
            ),
        )
    }

    @Test
    fun `rate limit and server errors are retryable`() {
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.HTTP, statusCode = 429)
            ),
        )
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.HTTP, statusCode = 503)
            ),
        )
    }

    @Test
    fun `client errors are not retryable`() {
        assertEquals(
            false,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.HTTP, statusCode = 400)
            ),
        )
        assertEquals(
            false,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                ApiException(ApiFailureKind.HTTP, statusCode = 404)
            ),
        )
    }

    @Test
    fun `io errors are retryable, other errors are not`() {
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(IOException("eof")),
        )
        assertEquals(
            false,
            AttachmentTransferErrorPolicy.isRetryableTransferError(RuntimeException("boom")),
        )
    }

    @Test
    fun `claim failure retries while still pending`() {
        listOf("QUEUED", "UPLOADING", "FAILED").forEach { state ->
            assertEquals(
                AttachmentTransferDisposition.RETRY,
                AttachmentTransferErrorPolicy.claimFailureDisposition(state),
            )
        }
    }

    @Test
    fun `claim failure ends when row left pending states`() {
        listOf("READY", "PAUSED", "SENDING", null).forEach { state ->
            assertEquals(
                AttachmentTransferDisposition.SUCCESS,
                AttachmentTransferErrorPolicy.claimFailureDisposition(state),
            )
        }
    }
}
