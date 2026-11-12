package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.session.CallSessionMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CallSessionControllerTest {

    private class Fixture {
        val gate = CallSessionGate()
        val machine = CallSessionMachine()
        var domainSession = 0L
        var callSession = 0L
        var ending = false
        val manager = Any()
        var currentManager: Any? = manager
        var clearedIdempotency = 0
        var clearedInbound = 0
        var begunOutbound = 0
        var resetIce = 0

        fun controller() = CallSessionController(
            callSessionGate = gate,
            callSessionMachine = machine,
            resetIceRecoveryAttempts = { resetIce++ },
            clearSignalingIdempotency = { clearedIdempotency++ },
            clearInboundSignalingCursors = { clearedInbound++ },
            beginOutboundSignalingCursor = { begunOutbound++ },
            setActiveDomainSession = { domainSession = it },
            setActiveCallSession = { callSession = it },
            endingCall = { ending },
            currentManager = { currentManager },
        )
    }

    @Test
    fun beginOutgoingSetsBothSessionsAndClearsCursors() {
        val f = Fixture()
        val session = f.controller().beginCallSession("peer-1")
        assertEquals(1L, f.domainSession)
        assertEquals(session, f.callSession)
        assertTrue(f.gate.isCurrent(session))
        assertEquals(1, f.clearedIdempotency)
        assertEquals(1, f.clearedInbound)
        assertEquals(1, f.begunOutbound)
        assertEquals(1, f.resetIce)
        assertEquals(1L, f.machine.snapshot().epoch)
        assertFalse(f.machine.snapshot().incoming)
    }

    @Test
    fun beginIncomingUsesIncomingSnapshot() {
        val f = Fixture()
        f.controller().beginCallSession("peer-1", incoming = true)
        assertTrue(f.machine.snapshot().incoming)
    }

    @Test
    fun isCurrentCallSessionChecksManagerIdentity() {
        val f = Fixture()
        val c = f.controller()
        val session = c.beginCallSession("peer-1")
        assertTrue(c.isCurrentCallSession(session))
        assertTrue(c.isCurrentCallSession(session, f.manager))
        assertFalse(c.isCurrentCallSession(session, Any()))
        f.currentManager = null
        assertFalse(c.isCurrentCallSession(session, f.manager))
    }

    @Test
    fun isCurrentCallSessionFalseWhenEndingOrInvalidated() {
        val f = Fixture()
        val c = f.controller()
        val session = c.beginCallSession("peer-1")
        f.ending = true
        assertFalse(c.isCurrentCallSession(session))
        f.ending = false
        assertTrue(c.isCurrentCallSession(session))
        f.gate.invalidate(session)
        assertFalse(c.isCurrentCallSession(session))
    }

    @Test
    fun newCallIdHasPrefixAndIsUnique() {
        val c = Fixture().controller()
        val first = c.newCallId()
        val second = c.newCallId()
        assertTrue(first.startsWith("call_"))
        assertEquals(37, first.length)
        assertNotEquals(first, second)
    }
}
