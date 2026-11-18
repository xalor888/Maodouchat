package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSessionGateTest {

    @Test
    fun `released call generation cannot affect the next call`() {
        val gate = CallSessionGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.invalidate(first))
        assertFalse(gate.isCurrent(first))

        val second = gate.begin()
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.invalidate(first))
        assertTrue(gate.isCurrent(second))
    }
}
