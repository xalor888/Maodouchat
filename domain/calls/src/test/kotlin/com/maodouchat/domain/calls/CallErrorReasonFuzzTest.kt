package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallErrorReasonFuzzTest {

    private val random = Random(0xCA11_2026L)

    private val codePool = listOf(
        "CALL_INVITE_RATE_LIMITED",
        "call_invite_rate_limited",
        "CALL_INVITE_RATE_LIMITED ",
        " CALL_INVITE_RATE_LIMITED",
        "CALL_INVITE_RATE_LIMITEDX",
        "SERVER_ERROR",
        "",
        null,
    )

    private fun randomRetryAfter(): Long? =
        when (random.nextInt(6)) {
            0 -> null
            1 -> 0L
            2 -> -1L
            3 -> Long.MAX_VALUE
            4 -> Long.MIN_VALUE
            else -> random.nextLong(1, 3600)
        }

    private fun randomMessage(): String? =
        when (random.nextInt(6)) {
            0 -> null
            1 -> ""
            2 -> "boom"
            3 -> "  padded message  "
            4 -> "错误：网络异常 \u00e9\u4e2d\u6587"
            else -> (1..random.nextInt(1, 12)).map {
                ('a'..'z').random(random)
            }.joinToString("")
        }

    private fun randomThrowable(): Throwable =
        if (random.nextBoolean()) {
            SignalingException(
                FailureOperation.entries.random(random),
                statusCode = if (random.nextBoolean()) null else random.nextInt(-1, 600),
                code = codePool.random(random),
                retryAfterSeconds = randomRetryAfter(),
            )
        } else {
            when (random.nextInt(3)) {
                0 -> RuntimeException(randomMessage())
                1 -> IllegalStateException(randomMessage())
                else -> Exception(randomMessage())
            }
        }

    // resolve 四段判定的逐字复述：限流码精确命中才走限流分支（缺省 60 秒）；
    // 其余信令异常一律网络错误；非信令异常有 message 就原样透传，否则网络错误。
    private fun oracleResolve(error: Throwable): CallErrorText =
        if (error is SignalingException && error.code == "CALL_INVITE_RATE_LIMITED") {
            CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(error.retryAfterSeconds ?: 60))
        } else if (error is SignalingException) {
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR)
        } else if (error.message != null) {
            CallErrorText.Literal(error.message!!)
        } else {
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR)
        }

    @Test
    fun `resolve matches verbatim oracle over random throwables`() {
        repeat(300) {
            val error = randomThrowable()
            assertEquals(
                oracleResolve(error),
                CallErrorReason.resolve(error),
                "error=$error code=${(error as? SignalingException)?.code}",
            )
        }
    }

    @Test
    fun `rate limit code must match exactly`() {
        val nearMisses = listOf(
            "call_invite_rate_limited",
            "CALL_INVITE_RATE_LIMITED ",
            " CALL_INVITE_RATE_LIMITED",
            "CALL_INVITE_RATE_LIMITEDX",
            "",
        )
        for (code in nearMisses) {
            val error = SignalingException(FailureOperation.SEND, code = code)
            assertEquals(
                CallErrorText.Res(CallErrorMessage.NETWORK_ERROR),
                CallErrorReason.resolve(error),
                "code=$code",
            )
        }
        assertEquals(
            CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(60L)),
            CallErrorReason.resolve(
                SignalingException(FailureOperation.SEND, code = "CALL_INVITE_RATE_LIMITED"),
            ),
        )
    }

    @Test
    fun `retryAfter defaults to 60 only when null`() {
        repeat(100) {
            val retryAfter = randomRetryAfter()
            val error = SignalingException(
                FailureOperation.SEND,
                code = "CALL_INVITE_RATE_LIMITED",
                retryAfterSeconds = retryAfter,
            )
            assertEquals(
                CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(retryAfter ?: 60L)),
                CallErrorReason.resolve(error),
                "retryAfter=$retryAfter",
            )
        }
    }

    @Test
    fun `signaling exception message names the operation in lowercase`() {
        for (operation in FailureOperation.entries) {
            val error = SignalingException(
                operation,
                statusCode = 500,
                code = "X",
                retryAfterSeconds = 7L,
            )
            assertEquals(
                "WebRTC signaling ${operation.name.lowercase()} failed",
                error.message,
                "operation=$operation",
            )
            assertEquals(500, error.statusCode)
            assertEquals("X", error.code)
            assertEquals(7L, error.retryAfterSeconds)
            assertTrue(error is Exception)
        }
    }

    @Test
    fun `generic errors pass the message through verbatim`() {
        repeat(200) {
            val message = randomMessage()
            val error = RuntimeException(message)
            assertEquals(
                if (message == null) {
                    CallErrorText.Res(CallErrorMessage.NETWORK_ERROR)
                } else {
                    CallErrorText.Literal(message)
                },
                CallErrorReason.resolve(error),
                "message=$message",
            )
        }
    }
}
