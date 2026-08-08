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
}
