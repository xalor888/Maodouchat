package com.maodouchat.domain.calls

import com.maodouchat.core.model.FailureOperation
import com.maodouchat.core.model.SignalingException
import kotlin.test.Test
import kotlin.test.assertEquals

class CallErrorReasonTest {

    @Test
    fun rateLimitedKeepsRetryAfterSeconds() {
        val error = SignalingException(
            FailureOperation.SEND,
            code = "CALL_INVITE_RATE_LIMITED",
            retryAfterSeconds = 45,
        )
        assertEquals(
            CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(45L)),
            CallErrorReason.resolve(error),
        )
    }

    @Test
    fun rateLimitedWithoutRetryAfterDefaultsTo60() {
        val error = SignalingException(FailureOperation.SEND, code = "CALL_INVITE_RATE_LIMITED")
        assertEquals(
            CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(60L)),
            CallErrorReason.resolve(error),
        )
    }

    @Test
    fun otherSignalingErrorMapsToNetworkError() {
        val error = SignalingException(FailureOperation.FETCH, statusCode = 500, code = "SERVER_ERROR")
        assertEquals(
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR),
            CallErrorReason.resolve(error),
        )
    }

    @Test
    fun genericErrorPassesMessageThrough() {
        assertEquals(
            CallErrorText.Literal("boom"),
            CallErrorReason.resolve(RuntimeException("boom")),
        )
    }

    @Test
    fun genericErrorWithoutMessageMapsToNetworkError() {
        assertEquals(
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR),
            CallErrorReason.resolve(RuntimeException()),
        )
    }
}
