package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.domain.messaging.OutboxRetryPolicy.RetryTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxRetryPolicyTest {

    private fun apiError(serverCode: String?) = ApiException(
        kind = ApiFailureKind.HTTP,
        statusCode = 400,
        serverCode = serverCode,
    )

    @Test
    fun `prepare phase failure always re-encrypts`() {
        val decision = OutboxRetryPolicy.decide(
            error = IllegalStateException("boom"),
            failedWhilePreparing = true,
            attempts = 1,
            now = 10_000L,
        )
        assertEquals(RetryTarget.RE_ENCRYPT, decision.target)
        assertEquals(11_000L, decision.nextAttemptAt)
        assertEquals("boom", decision.errorCode)
    }

    @Test
    fun `send phase coverage mismatch re-encrypts`() {
        val decision = OutboxRetryPolicy.decide(
            error = apiError("DEVICE_COVERAGE_MISMATCH"),
            failedWhilePreparing = false,
            attempts = 2,
            now = 10_000L,
        )
        assertEquals(RetryTarget.RE_ENCRYPT, decision.target)
        assertEquals(12_000L, decision.nextAttemptAt)
    }

    @Test
    fun `send phase revision mismatch re-encrypts`() {
        val decision = OutboxRetryPolicy.decide(
            error = apiError("GROUP_REVISION_MISMATCH"),
            failedWhilePreparing = false,
            attempts = 1,
            now = 10_000L,
        )
        assertEquals(RetryTarget.RE_ENCRYPT, decision.target)
        assertEquals(11_000L, decision.nextAttemptAt)
    }

    @Test
    fun `send phase other error resends as is`() {
        val decision = OutboxRetryPolicy.decide(
            error = apiError("RATE_LIMITED"),
            failedWhilePreparing = false,
            attempts = 1,
            now = 10_000L,
        )
        assertEquals(RetryTarget.RESEND, decision.target)
        assertEquals(11_000L, decision.nextAttemptAt)
    }

    @Test
    fun `sender key coverage pending retries immediately`() {
        val decision = OutboxRetryPolicy.decide(
            error = IllegalStateException(OutboxFailurePolicy.SENDER_KEY_COVERAGE_PENDING),
            failedWhilePreparing = false,
            attempts = 3,
            now = 10_000L,
        )
        assertEquals(RetryTarget.RESEND, decision.target)
        assertEquals(10_000L, decision.nextAttemptAt)
        assertEquals(
            OutboxFailurePolicy.SENDER_KEY_COVERAGE_PENDING,
            decision.errorCode,
        )
    }

    @Test
    fun `blank error message falls back to class name`() {
        val decision = OutboxRetryPolicy.decide(
            error = IllegalStateException("   "),
            failedWhilePreparing = false,
            attempts = 1,
            now = 10_000L,
        )
        assertEquals("IllegalStateException", decision.errorCode)
    }
}
