package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentExecuteDispositionPolicyTest {

    @Test
    fun `ready promotes to finalize`() {
        assertEquals(
            PromoteDisposition.Finalize,
            AttachmentExecuteDispositionPolicy.promoteDisposition("READY", hasCompletedUpload = true),
        )
    }

    @Test
    fun `sending promotes to finalize`() {
        assertEquals(
            PromoteDisposition.Finalize,
            AttachmentExecuteDispositionPolicy.promoteDisposition("SENDING", hasCompletedUpload = true),
        )
    }

    @Test
    fun `paused is done`() {
        assertEquals(
            PromoteDisposition.Done,
            AttachmentExecuteDispositionPolicy.promoteDisposition("PAUSED", hasCompletedUpload = true),
        )
    }

    @Test
    fun `failed but uploaded is done`() {
        assertEquals(
            PromoteDisposition.Done,
            AttachmentExecuteDispositionPolicy.promoteDisposition("FAILED", hasCompletedUpload = true),
        )
    }

    @Test
    fun `failed and not uploaded retries`() {
        assertEquals(
            PromoteDisposition.Retry,
            AttachmentExecuteDispositionPolicy.promoteDisposition("FAILED", hasCompletedUpload = false),
        )
    }

    @Test
    fun `queued and not uploaded retries`() {
        assertEquals(
            PromoteDisposition.Retry,
            AttachmentExecuteDispositionPolicy.promoteDisposition("QUEUED", hasCompletedUpload = false),
        )
    }

    @Test
    fun `unknown state without upload retries`() {
        assertEquals(
            PromoteDisposition.Retry,
            AttachmentExecuteDispositionPolicy.promoteDisposition(null, hasCompletedUpload = false),
        )
    }

    @Test
    fun `unknown state with upload is done`() {
        assertEquals(
            PromoteDisposition.Done,
            AttachmentExecuteDispositionPolicy.promoteDisposition(null, hasCompletedUpload = true),
        )
    }

    @Test
    fun `server error retries below max`() {
        assertTrue(
            AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(
                runAttemptCount = 0,
                error = ApiException(ApiFailureKind.HTTP, statusCode = 500),
            ),
        )
    }

    @Test
    fun `server error does not retry at max`() {
        assertFalse(
            AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(
                runAttemptCount = 6,
                error = ApiException(ApiFailureKind.HTTP, statusCode = 500),
            ),
        )
    }

    @Test
    fun `client error does not retry`() {
        assertFalse(
            AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(
                runAttemptCount = 0,
                error = ApiException(ApiFailureKind.HTTP, statusCode = 400),
            ),
        )
    }

    @Test
    fun `io error retries below max`() {
        assertTrue(
            AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(
                runAttemptCount = 5,
                error = IOException("reset"),
            ),
        )
    }

    @Test
    fun `plain error does not retry`() {
        assertFalse(
            AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(
                runAttemptCount = 0,
                error = IllegalStateException("boom"),
            ),
        )
    }

    @Test
    fun `uploading row requeues`() {
        assertTrue(AttachmentExecuteDispositionPolicy.shouldRequeueForRetry("UPLOADING"))
    }

    @Test
    fun `queued row requeues`() {
        assertTrue(AttachmentExecuteDispositionPolicy.shouldRequeueForRetry("QUEUED"))
    }

    @Test
    fun `ready row does not requeue`() {
        assertFalse(AttachmentExecuteDispositionPolicy.shouldRequeueForRetry("READY"))
    }

    @Test
    fun `missing row does not requeue`() {
        assertFalse(AttachmentExecuteDispositionPolicy.shouldRequeueForRetry(null))
    }
}
