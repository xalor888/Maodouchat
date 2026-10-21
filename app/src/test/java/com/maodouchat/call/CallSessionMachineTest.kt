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
}
