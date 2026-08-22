package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-41：群玩法 / AI enhance 共用的 [BoundedRateLimiter] 边界。
 * 类是 internal，测试必须与生产代码同包。
 */
class BoundedRateLimiterTest {

    @Test
    fun `blank key and non-positive quota are rejected`() {
        val limiter = BoundedRateLimiter()
        val now = 1_000L
        assertFalse(limiter.acquire("", maxPerMinute = 10, now = now))
        assertFalse(limiter.acquire("   ", maxPerMinute = 10, now = now))
        assertFalse(limiter.acquire("user:chat:checkin", maxPerMinute = 0, now = now))
        assertFalse(limiter.acquire("user:chat:checkin", maxPerMinute = -1, now = now))
    }

    @Test
    fun `same key is capped per window while other keys stay independent`() {
        val limiter = BoundedRateLimiter()
        val now = 20_000L
        val key = "u1:g1:checkin"
        assertTrue(limiter.acquire(key, maxPerMinute = 2, now = now))
        assertTrue(limiter.acquire(key, maxPerMinute = 2, now = now))
        assertFalse(limiter.acquire(key, maxPerMinute = 2, now = now))
        assertTrue(limiter.acquire("u2:g1:checkin", maxPerMinute = 2, now = now))
    }

    @Test
    fun `sliding window allows the next acquire after 60s`() {
        val limiter = BoundedRateLimiter()
        val t0 = 40_000L
        val key = "u1:g1:pk_vote"
        assertTrue(limiter.acquire(key, maxPerMinute = 1, now = t0))
        assertFalse(limiter.acquire(key, maxPerMinute = 1, now = t0 + 59_999L))
        assertFalse(limiter.acquire(key, maxPerMinute = 1, now = t0 + 60_000L))
        assertTrue(limiter.acquire(key, maxPerMinute = 1, now = t0 + 60_001L))
    }
}
