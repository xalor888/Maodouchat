package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalingFailureModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-', '.', 'é', '中', '文')
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomNullableStatus(): Int? =
        when (random.nextInt(4)) {
            0 -> null
            1 -> random.nextInt(-1, 600)
            2 -> random.nextInt()
            else -> random.nextInt(100, 600)
        }

    private fun randomNullableRetryAfter(): Long? =
        when (random.nextInt(4)) {
            0 -> null
            1 -> 0L
            2 -> Long.MAX_VALUE
            else -> random.nextLong(-1000, 1000)
        }

    private fun randomException(): SignalingException =
        SignalingException(
            operation = FailureOperation.entries.random(random),
            statusCode = randomNullableStatus(),
            code = if (random.nextBoolean()) null else randomString(),
            retryAfterSeconds = randomNullableRetryAfter(),
        )

    @Test
    fun `exception message is pinned per operation`() {
        assertEquals("WebRTC signaling send failed", SignalingException(FailureOperation.SEND).message)
        assertEquals("WebRTC signaling fetch failed", SignalingException(FailureOperation.FETCH).message)
        assertEquals("WebRTC signaling hang_up failed", SignalingException(FailureOperation.HANG_UP).message)
        for (operation in FailureOperation.entries) {
            assertEquals(
                "WebRTC signaling ${operation.name.lowercase()} failed",
                SignalingException(operation).message,
            )
        }
    }

    @Test
    fun `optional fields default to null and are retained`() {
        val bare = SignalingException(FailureOperation.SEND)
        assertNull(bare.statusCode)
        assertNull(bare.code)
        assertNull(bare.retryAfterSeconds)
        repeat(200) {
            val error = randomException()
            val rebuilt = SignalingException(
                operation = error.operation,
                statusCode = error.statusCode,
                code = error.code,
                retryAfterSeconds = error.retryAfterSeconds,
            )
            assertEquals(error.operation, rebuilt.operation)
            assertEquals(error.statusCode, rebuilt.statusCode)
            assertEquals(error.code, rebuilt.code)
            assertEquals(error.retryAfterSeconds, rebuilt.retryAfterSeconds)
            assertEquals(error.message, rebuilt.message)
            // 文案只由 operation 决定，可选字段不改文案。
            assertEquals("WebRTC signaling ${error.operation.name.lowercase()} failed", error.message)
        }
    }

    @Test
    fun `throwing preserves the operation`() {
        repeat(100) {
            val error = randomException()
            val caught = assertFailsWith<SignalingException> { throw error }
            assertEquals(error.operation, caught.operation)
            assertEquals(error.message, caught.message)
            assertTrue(caught is Exception)
        }
    }
}
