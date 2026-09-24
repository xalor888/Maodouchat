package com.maodouchat.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * G328c：`core/network` 此前**零测试**（零覆盖模块之一）。
 *
 * `NetworkResult` 是「领域错误」的冻结接口：调用方靠 `Kind` 决定重试/登出/提示，
 * 靠 `retryAfterMillis` 决定退避。这里钉住的是**错误信息不被吞掉**这个性质——
 * `getOrThrow` 抛出的异常必须带着原始 failure，否则上层只能看到一句泛泛的 message。
 */
class NetworkResultTest {

    @Test
    fun `success carries its value through getOrThrow`() {
        val result: NetworkResult<Int> = NetworkResult.Success(7)
        assertEquals(7, result.getOrThrow())
    }

    @Test
    fun `failure throws and the exception keeps the original failure`() {
        val failure = NetworkResult.Failure(
            kind = NetworkResult.Kind.RATE_LIMITED,
            message = "too many",
            statusCode = 429,
            retryAfterMillis = 30_000L,
        )
        try {
            failure.getOrThrow()
            fail("Failure 必须抛出")
        } catch (e: NetworkFailureException) {
            assertSame("异常必须携带原始 failure（含 kind/status/retryAfter）", failure, e.failure)
            assertEquals(NetworkResult.Kind.RATE_LIMITED, e.failure.kind)
            assertEquals(429, e.failure.statusCode)
            assertEquals(30_000L, e.failure.retryAfterMillis)
            assertEquals("too many", e.message)
        }
    }

    @Test
    fun `failure without message falls back to the kind name`() {
        val failure = NetworkResult.Failure(kind = NetworkResult.Kind.CANCELLED)
        assertNull(failure.message)
        assertEquals("CANCELLED", NetworkFailureException(failure).message)
    }

    @Test
    fun `every kind is representable`() {
        // 枚举是给调用方做分支判断用的：新增/改名会让 when 分支失效，
        // 这里把「全部取值都能构造出 Failure」钉住。
        val kinds = NetworkResult.Kind.entries
        assertTrue("Kind 至少应覆盖审计要求的九类", kinds.size >= 9)
        kinds.forEach { kind ->
            val f = NetworkResult.Failure(kind)
            assertEquals(kind, f.kind)
        }
    }

    @Test
    fun `success and failure are distinguishable by type`() {
        val ok: NetworkResult<String> = NetworkResult.Success("v")
        val bad: NetworkResult<String> = NetworkResult.Failure(NetworkResult.Kind.TRANSIENT)
        assertTrue(ok is NetworkResult.Success)
        assertTrue(bad is NetworkResult.Failure)
    }
}
