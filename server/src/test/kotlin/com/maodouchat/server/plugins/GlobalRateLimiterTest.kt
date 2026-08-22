package com.maodouchat.server.plugins

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XAL-41：全局滑动窗口限流（空白 IP / remaining / Retry-After / 滑窗释放）。
 * 使用独立实例，不碰 [GlobalRateLimiter.getInstance]，避免污染进程单例。
 */
class GlobalRateLimiterTest {

    private var limiter: GlobalRateLimiter? = null
    private var lifecycleId: Long? = null

    private fun newLimiter(maxPerMinute: Int): GlobalRateLimiter {
        val created = GlobalRateLimiter(maxPerMinute)
        lifecycleId = created.start()
        limiter = created
        return created
    }

    @AfterTest
    fun tearDown() {
        val instance = limiter
        val id = lifecycleId
        if (instance != null && id != null) {
            instance.shutdown(id)
        }
        limiter = null
        lifecycleId = null
    }

    @Test
    fun `blank ip is rejected with remaining 0 and no retry-after`() {
        val limiter = newLimiter(maxPerMinute = 5)
        val now = 1_000L
        val blank = limiter.tryAcquire("", now)
        assertFalse(blank.allowed)
        assertEquals(0, blank.remaining)
        assertNull(blank.retryAfterSeconds)

        val whitespace = limiter.tryAcquire("   ", now)
        assertFalse(whitespace.allowed)
        assertEquals(0, whitespace.remaining)
        assertNull(whitespace.retryAfterSeconds)
    }

    @Test
    fun `remaining counts down then reject carries retry-after`() {
        val limiter = newLimiter(maxPerMinute = 2)
        val now = 10_000L

        val first = limiter.tryAcquire("1.2.3.4", now)
        assertTrue(first.allowed)
        assertEquals(1, first.remaining)
        assertNull(first.retryAfterSeconds)

        val second = limiter.tryAcquire("1.2.3.4", now)
        assertTrue(second.allowed)
        assertEquals(0, second.remaining)

        val third = limiter.tryAcquire("1.2.3.4", now)
        assertFalse(third.allowed)
        assertEquals(0, third.remaining)
        val retryAfter = third.retryAfterSeconds
        assertNotNull(retryAfter)
        assertTrue(retryAfter >= 1L)

        val otherIp = limiter.tryAcquire("9.9.9.9", now)
        assertTrue(otherIp.allowed)
        assertEquals(1, otherIp.remaining)
    }

    @Test
    fun `sliding window frees a slot after 60s`() {
        val limiter = newLimiter(maxPerMinute = 1)
        val t0 = 50_000L
        assertTrue(limiter.tryAcquire("10.0.0.1", t0).allowed)
        assertFalse(limiter.tryAcquire("10.0.0.1", t0 + 59_999L).allowed)
        // 窗口是 now - 60_000 开区间：恰好 60s 时旧戳仍在窗内
        assertFalse(limiter.tryAcquire("10.0.0.1", t0 + 60_000L).allowed)
        val afterWindow = limiter.tryAcquire("10.0.0.1", t0 + 60_001L)
        assertTrue(afterWindow.allowed)
        assertEquals(0, afterWindow.remaining)
    }
}
