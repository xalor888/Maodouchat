package com.maodouchat.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockPolicyTest {
    @Test
    fun `disabled or logged out sessions never show the privacy gate`() {
        assertFalse(AppLockPolicy.shouldLock(false, true, false, 0L, 60_000L, 100_000L))
        assertFalse(AppLockPolicy.shouldLock(true, false, false, 0L, 60_000L, 100_000L))
    }

    @Test
    fun `cold process start locks an enabled logged in account`() {
        assertTrue(AppLockPolicy.shouldLock(true, true, false, 0L, 300_000L, 100_000L))
    }

    @Test
    fun `authenticated foreground session stays unlocked without background timestamp`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 0L, 300_000L, 100_000L))
    }

    @Test
    fun `background timeout locks exactly at threshold`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 69_999L))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 70_000L))
    }

    @Test
    fun `wall clock rollback fails closed`() {
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 100_000L, 60_000L, 90_000L))
    }

    @Test
    fun `zero timeout coerces to one millisecond`() {
        // 超时 0 按 1ms 算：刚好回到前台不锁，1ms 后锁。
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 0L, 10_000L))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 10_000L, 0L, 10_001L))
    }

    @Test
    fun `negative timeout also coerces to one millisecond`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, -5_000L, 10_000L))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 10_000L, -5_000L, 10_001L))
    }

    @Test
    fun `negative background timestamp counts as never backgrounded`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, -1L, 60_000L, 100_000L))
    }

    @Test
    fun `unauthenticated session locks even with a fresh background timestamp`() {
        // 认证检查先于超时计算：刚回前台但没通过验证照样锁。
        assertTrue(AppLockPolicy.shouldLock(true, true, false, 99_999L, 60_000L, 100_000L))
    }

    @Test
    fun `mid-window background stays unlocked`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 40_000L))
    }
}
