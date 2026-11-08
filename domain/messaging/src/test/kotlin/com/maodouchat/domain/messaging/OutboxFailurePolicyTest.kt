package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxFailurePolicyTest {

    private fun apiError(serverCode: String?) = ApiException(
        kind = ApiFailureKind.HTTP,
        statusCode = 400,
        serverCode = serverCode,
    )

    @Test
    fun `group control message with revision mismatch is discarded`() {
        assertTrue(
            OutboxFailurePolicy.shouldDiscardStaleGroupControl(
                apiError("GROUP_REVISION_MISMATCH"),
                "SENDER_KEY",
            ),
        )
        assertTrue(
            OutboxFailurePolicy.shouldDiscardStaleGroupControl(
                apiError("GROUP_REVISION_MISMATCH"),
                "KEY_REQUEST",
            ),
        )
    }

    @Test
    fun `non group control message with revision mismatch is not discarded`() {
        assertFalse(
            OutboxFailurePolicy.shouldDiscardStaleGroupControl(
                apiError("GROUP_REVISION_MISMATCH"),
                "TEXT",
            ),
        )
    }

    @Test
    fun `group control message with another server code is not discarded`() {
        assertFalse(
            OutboxFailurePolicy.shouldDiscardStaleGroupControl(
                apiError("DEVICE_COVERAGE_MISMATCH"),
                "SENDER_KEY",
            ),
        )
    }

    @Test
    fun `non api error is never discarded`() {
        assertFalse(
            OutboxFailurePolicy.shouldDiscardStaleGroupControl(
                IllegalStateException("GROUP_REVISION_MISMATCH"),
                "SENDER_KEY",
            ),
        )
    }

    @Test
    fun `coverage or revision mismatch needs fresh encryption`() {
        assertTrue(OutboxFailurePolicy.needsFreshEncryption(apiError("DEVICE_COVERAGE_MISMATCH")))
        assertTrue(OutboxFailurePolicy.needsFreshEncryption(apiError("GROUP_REVISION_MISMATCH")))
    }

    @Test
    fun `other server errors retry the send as is`() {
        assertFalse(OutboxFailurePolicy.needsFreshEncryption(apiError("RATE_LIMITED")))
        assertFalse(OutboxFailurePolicy.needsFreshEncryption(apiError(null)))
        assertFalse(OutboxFailurePolicy.needsFreshEncryption(IllegalStateException("boom")))
    }

    @Test
    fun `exact sender key coverage pending message is recognised`() {
        assertTrue(
            OutboxFailurePolicy.isSenderKeyCoveragePending(
                OutboxFailurePolicy.SENDER_KEY_COVERAGE_PENDING,
            ),
        )
    }

    @Test
    fun `other or missing messages are not sender key coverage pending`() {
        assertFalse(OutboxFailurePolicy.isSenderKeyCoveragePending("sender_key_coverage_pending "))
        assertFalse(OutboxFailurePolicy.isSenderKeyCoveragePending("boom"))
        assertFalse(OutboxFailurePolicy.isSenderKeyCoveragePending(null))
    }
}
