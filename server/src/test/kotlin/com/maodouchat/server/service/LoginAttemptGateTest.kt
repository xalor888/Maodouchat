package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LoginAttemptGate] 状态机单测（假时钟；对照 8.31/8.40/8.51 修复语义）。
 */
class LoginAttemptGateTest {

    @Test
    fun `five failures lock, sixth is rejected`() {
        var now = 1_000L
        val gate = LoginAttemptGate(clock = { now })
        val key = gate.key("a@x.test", "1.1.1.1")
        repeat(4) {
            gate.recordFailure("a@x.test", "1.1.1.1")
            assertFalse(gate.isLocked(key))
        }
        gate.recordFailure("a@x.test", "1.1.1.1")
        assertTrue(gate.isLocked(key))
    }

    @Test
    fun `lock is per ip, victim ip unaffected`() {
        var now = 1_000L
        val gate = LoginAttemptGate(clock = { now })
        repeat(5) { gate.recordFailure("a@x.test", "9.9.9.9") }
        assertTrue(gate.isLocked(gate.key("a@x.test", "9.9.9.9")))
        assertFalse(gate.isLocked(gate.key("a@x.test", "1.1.1.1")))
    }

    @Test
    fun `expiry clears only previously locked entries`() {
        var now = 1_000L
        val gate = LoginAttemptGate(clock = { now })
        val locked = gate.key("a@x.test", "1.1.1.1")
        val fresh = gate.key("b@x.test", "1.1.1.1")
        repeat(5) { gate.recordFailure("a@x.test", "1.1.1.1") }
        gate.recordFailure("b@x.test", "1.1.1.1")
        assertTrue(gate.isLocked(locked))

        now += LoginAttemptGate.LOCK_MS + 1
        // 未锁定过的条目不得被清除（否则计数清零、锁定永不触发）。
        gate.clearIfExpired(fresh)
        assertEquals(2, gate.snapshotSize())
        // 锁定过期后清除，下一次失败从零计数。
        gate.clearIfExpired(locked)
        assertEquals(1, gate.snapshotSize())
        assertFalse(gate.isLocked(locked))
    }

    @Test
    fun `success clears counter`() {
        var now = 1_000L
        val gate = LoginAttemptGate(clock = { now })
        val key = gate.key("a@x.test", "1.1.1.1")
        repeat(4) { gate.recordFailure("a@x.test", "1.1.1.1") }
        gate.clear(key)
        assertEquals(0, gate.snapshotSize())
        assertFalse(gate.isLocked(key))
    }

    @Test
    fun `sweep bounds memory`() {
        var now = 1_000L
        val gate = LoginAttemptGate(clock = { now })
        repeat(3) { i -> gate.recordFailure("u$i@x.test", "1.1.1.1") }
        assertEquals(3, gate.snapshotSize())
        // 推进超过保留期 + 宽限，触发清理。
        now += LoginAttemptGate.LOCK_MS + 61_000L
        gate.sweep()
        assertEquals(0, gate.snapshotSize())
    }
}
