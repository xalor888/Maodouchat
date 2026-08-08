package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallInviteRateLimiterTest {
    @Test
    fun `same group call id counts once while new sessions are bounded`() {
        var now = 1_000L
        val limiter = CallInviteRateLimiter(maxPerMinute = 2, maxPerTenMinutes = 3) { now }

        assertTrue(limiter.tryAcquire("u1", "call-a").allowed)
        assertTrue(limiter.tryAcquire("u1", "call-a").allowed)
        assertTrue(limiter.tryAcquire("u1", "call-b").allowed)
        assertFalse(limiter.tryAcquire("u1", "call-c").allowed)
        now += 60_000
        assertTrue(limiter.tryAcquire("u1", "call-c").allowed)
        assertFalse(limiter.tryAcquire("u1", "call-d").allowed)
    }

    @Test
    fun `only direct or explicit group invite offers consume quota`() {
        assertTrue(CallInviteRateLimiter.isInitialInvite("offer", "", false))
        assertTrue(CallInviteRateLimiter.isInitialInvite("offer", "group-a", true))
        assertFalse(CallInviteRateLimiter.isInitialInvite("offer", "group-a", false))
        assertFalse(CallInviteRateLimiter.isInitialInvite("answer", "group-a", true))
        assertTrue(CallInviteRateLimiter.sessionKey("call-a", "group-a", "u2") == CallInviteRateLimiter.sessionKey("call-a", "group-a", "u3"))
        assertFalse(CallInviteRateLimiter.sessionKey("call-a", "", "u2") == CallInviteRateLimiter.sessionKey("call-a", "", "u3"))
    }
}
