package com.maodouchat.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSessionMachineTest {
    @Test
    fun outgoingCallFollowsLifecycle() {
        val machine = CallSessionMachine()
        val started = machine.beginOutgoing("peer-1")

        assertEquals(CallSessionMachine.Phase.OUTGOING_RINGING, started.phase)
        assertTrue(machine.isCurrent(started.epoch))
        assertEquals(CallSessionMachine.Phase.CONNECTING, machine.markConnecting(started.epoch).phase)
        assertEquals(CallSessionMachine.Phase.CONNECTED, machine.markConnected(started.epoch).phase)
        assertEquals(CallSessionMachine.Phase.ENDING, machine.beginEnding(started.epoch).phase)
        assertEquals(CallSessionMachine.Phase.ENDED, machine.finish(started.epoch).phase)
        assertFalse(machine.isCurrent(started.epoch))
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
        assertEquals(CallSessionMachine.Phase.OUTGOING_RINGING, machine.snapshot().phase)
    }
}
