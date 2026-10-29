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

    @Test
    fun `unauthenticated gate fires before the background timestamp check`() {
        // 未通过当前账号验证即锁：即便 backgroundAt=0（从未切后台）也不豁免。
        assertTrue(AppLockPolicy.shouldLock(true, true, false, 0L, 60_000L, 100_000L))
    }

    @Test
    fun `logged out wins even when authenticated and timed out`() {
        // 账号已登出：即便曾验证通过且超时已过，也不弹锁。
        assertFalse(AppLockPolicy.shouldLock(true, false, true, 10_000L, 60_000L, 200_000L))
    }

    @Test
    fun `fresh foreground instant with a background timestamp stays unlocked`() {
        // elapsed=0：刚回到前台，超时窗口还没开始走。
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 10_000L))
    }

    @Test
    fun `enormous timeout never overflows into a lock`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 1_000L, Long.MAX_VALUE, 101_000L))
    }

    @Test
    fun `decision is stable across repeated calls`() {
        repeat(3) {
            assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 40_000L))
        }
    }

    @Test
    fun `wall clock rollback fails closed regardless of timeout size`() {
        // elapsed<0 先判锁：再大的超时窗口也豁免不了时钟回拨。
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 200_000L, Long.MAX_VALUE, 100_000L))
    }

    @Test
    fun `disabled outranks a wall clock rollback`() {
        // enabled 检查在前：开关关了，时钟回拨也不会误弹锁。
        assertFalse(AppLockPolicy.shouldLock(false, true, true, 100_000L, 60_000L, 90_000L))
    }

    @Test
    fun `explicit one millisecond timeout behaves like the coerced zero`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 1L, 10_000L))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 10_000L, 1L, 10_001L))
    }

    @Test
    fun `unauthenticated locks at the exact foreground instant`() {
        // 切账号后未验证：即使 elapsed=0（刚回前台）也直接锁。
        assertTrue(AppLockPolicy.shouldLock(true, true, false, 10_000L, 60_000L, 10_000L))
    }

    @Test
    fun `account switch to unauthenticated locks mid-window`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, 60_000L, 40_000L))
        assertTrue(AppLockPolicy.shouldLock(true, true, false, 10_000L, 60_000L, 40_000L))
    }

    @Test
    fun `long min timeout coerces to one millisecond`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, 10_000L, Long.MIN_VALUE, 10_000L))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, 10_000L, Long.MIN_VALUE, 10_001L))
    }

    @Test
    fun `logged out beats an unauthenticated session`() {
        // 登出检查在前：即便没通过验证也不弹锁。
        assertFalse(AppLockPolicy.shouldLock(true, false, false, 10_000L, 60_000L, 200_000L))
    }

    @Test
    fun `unauthenticated beats a never-backgrounded session`() {
        assertTrue(AppLockPolicy.shouldLock(true, true, false, -1L, 60_000L, 100_000L))
    }

    @Test
    fun `long min background timestamp counts as never backgrounded`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, Long.MIN_VALUE, 60_000L, 100_000L))
    }

    @Test
    fun `threshold still applies at the long max boundary`() {
        assertFalse(AppLockPolicy.shouldLock(true, true, true, Long.MAX_VALUE - 5_000L, 60_000L, Long.MAX_VALUE))
        assertTrue(AppLockPolicy.shouldLock(true, true, true, Long.MAX_VALUE - 5_000L, 5_000L, Long.MAX_VALUE))
    }

}
