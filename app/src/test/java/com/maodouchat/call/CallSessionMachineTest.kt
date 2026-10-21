package com.maodouchat.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSessionMachineTest {
    @Test
    fun outgoingCallFollowsLifecycle() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")

        assertEquals(Phase.OUTGOING_RINGING, started.phase)
        assertTrue(machine.isCurrent(started.epoch))
        assertEquals(Phase.CONNECTING, machine.markConnecting(started.epoch).phase)
        assertEquals(Phase.CONNECTED, machine.markConnected(started.epoch).phase)
        assertEquals(Phase.ENDING, machine.beginEnding(started.epoch).phase)
        assertEquals(Phase.ENDED, machine.finish(started.epoch).phase)
        assertFalse(machine.isCurrent(started.epoch))
    }

    @Test
    fun blankPeerIdIsRejected() {
        val machine = CallSessionMachine()
        assertFailsWith<IllegalStateException> { machine.beginOutgoing("") }
        assertFailsWith<IllegalStateException> { machine.beginIncoming("  ") }
    }

    @Test
    fun beginWhileActiveIsRejected() {
        val machine = CallSessionMachine()
        machine.beginOutgoing("peer-1")
        assertFailsWith<IllegalStateException> { machine.beginIncoming("peer-2") }
        assertEquals(Phase.OUTGOING_RINGING, machine.snapshot().phase)
    }

    @Test
    fun finishWithoutEndingIsNoop() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        val after = machine.finish(started.epoch)
        assertEquals(Phase.OUTGOING_RINGING, after.phase)
        assertTrue(machine.isCurrent(started.epoch))
    }

    @Test
    fun incomingCallFollowsLifecycle() {
        val machine = CallSessionMachine()
        val started = machine.beginIncoming("peer-1")

        assertEquals(Phase.INCOMING_RINGING, started.phase)
        assertTrue(started.incoming)
        assertEquals("peer-1", started.peerId)
        assertEquals(Phase.CONNECTING, machine.markConnecting(started.epoch).phase)
        assertEquals(Phase.CONNECTED, machine.markConnected(started.epoch).phase)
        assertEquals(Phase.ENDING, machine.beginEnding(started.epoch).phase)
        assertEquals(Phase.ENDED, machine.finish(started.epoch).phase)
        assertFalse(machine.isCurrent(started.epoch))
    }

    @Test
    fun epochStrictlyIncreasesAcrossSessions() {
        val machine = CallSessionMachine()
        val first = machine.beginOutgoing("peer-1")
        machine.beginEnding(first.epoch)
        machine.finish(first.epoch)
        val second = machine.beginOutgoing("peer-2")

        assertEquals(first.epoch + 1L, second.epoch)
        assertFalse(machine.isCurrent(first.epoch))
        assertTrue(machine.isCurrent(second.epoch))
        assertFalse(machine.isCurrent(0L))
    }

    @Test
    fun staleEpochCannotMutateNewSession() {
        val machine = CallSessionMachine()
        val first = machine.beginIncoming("peer-1")
        machine.beginEnding(first.epoch)
        machine.finish(first.epoch)
        val second = machine.beginOutgoing("peer-2")

        machine.markConnected(first.epoch)

        assertEquals(second.epoch, machine.snapshot().epoch)
        assertEquals(Phase.OUTGOING_RINGING, machine.snapshot().phase)
    }

    @Test
    fun staleEpochMarkConnectingIsNoop() {
        val machine = CallSessionMachine()
        val first = machine.beginOutgoing("peer-1")
        machine.beginEnding(first.epoch)
        machine.finish(first.epoch)
        val second = machine.beginOutgoing("peer-2")

        // 旧 epoch 的过渡指令不得改动新会话——重试重放的陈旧信令被隔离。
        val before = machine.snapshot()
        machine.markConnecting(first.epoch)
        assertEquals(before, machine.snapshot())
        assertEquals(second.epoch, machine.snapshot().epoch)
        assertEquals(Phase.OUTGOING_RINGING, machine.snapshot().phase)
    }

    @Test
    fun beginEndingOnIdleIsNoop() {
        val machine = CallSessionMachine()
        val before = machine.snapshot()

        machine.beginEnding(1L)

        assertEquals(before, machine.snapshot())
        assertEquals(Phase.IDLE, machine.snapshot().phase)
        assertFalse(machine.isCurrent(1L))
    }

    @Test
    fun doubleFinishStaysEndedAndNotCurrent() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.beginEnding(started.epoch)
        machine.finish(started.epoch)

        val again = machine.finish(started.epoch)

        assertEquals(Phase.ENDED, again.phase)
        assertFalse(machine.isCurrent(started.epoch))
    }

    @Test
    fun markConnectedTwiceIsIdempotent() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.markConnected(started.epoch)

        val after = machine.markConnected(started.epoch)

        assertEquals(Phase.CONNECTED, after.phase)
        assertEquals(started.epoch, after.epoch)
        assertTrue(machine.isCurrent(started.epoch))
    }

    @Test
    fun beginEndingWithWrongEpochIsNoop() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.markConnected(started.epoch)

        machine.beginEnding(started.epoch + 99L)

        assertEquals(Phase.CONNECTED, machine.snapshot().phase)
        assertTrue(machine.isCurrent(started.epoch))
    }

    @Test
    fun markConnectedDirectlyFromRingingSkipsConnecting() {
        // 成功：CONNECTED 允许从 RINGING 直达，不必经过 CONNECTING。
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")

        assertEquals(Phase.CONNECTED, machine.markConnected(started.epoch).phase)
        assertTrue(machine.isCurrent(started.epoch))
    }

    @Test
    fun beginWhileConnectingIsRejectedAndSnapshotUnchanged() {
        // 失败：CONNECTING 中再起呼叫被拒，快照不动。
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.markConnecting(started.epoch)

        assertFailsWith<IllegalStateException> { machine.beginIncoming("peer-2") }
        assertEquals(Phase.CONNECTING, machine.snapshot().phase)
        assertEquals(started.epoch, machine.snapshot().epoch)
    }

    @Test
    fun endingFromConnectingThenFinishEndsSession() {
        // 取消：接通前挂断——CONNECTING→ENDING→ENDED，会话收尾。
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.markConnecting(started.epoch)

        assertEquals(Phase.ENDING, machine.beginEnding(started.epoch).phase)
        assertEquals(Phase.ENDED, machine.finish(started.epoch).phase)
        assertFalse(machine.isCurrent(started.epoch))
    }

    @Test
    fun doubleBeginEndingStaysEndingAndCurrent() {
        // 重复：beginEnding 两次仍是 ENDING，会话还在（没真正结束）。
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")
        machine.markConnected(started.epoch)

        machine.beginEnding(started.epoch)
        val again = machine.beginEnding(started.epoch)

        assertEquals(Phase.ENDING, again.phase)
        assertTrue(machine.isCurrent(started.epoch))
    }

    @Test
    fun newSessionAfterEndingResetsPeerAndIncomingFlag() {
        // 切换：上轮去电结束后来电——peerId/incoming 随新会话重置，epoch 递增。
        val machine = CallSessionMachine()
        val first = machine.beginOutgoing("peer-1")
        machine.beginEnding(first.epoch)
        machine.finish(first.epoch)

        val second = machine.beginIncoming("peer-2")

        assertEquals(first.epoch + 1L, second.epoch)
        assertEquals("peer-2", second.peerId)
        assertTrue(second.incoming)
        assertEquals(Phase.INCOMING_RINGING, second.phase)
        assertFalse(machine.isCurrent(first.epoch))
        assertTrue(machine.isCurrent(second.epoch))
    }
}
