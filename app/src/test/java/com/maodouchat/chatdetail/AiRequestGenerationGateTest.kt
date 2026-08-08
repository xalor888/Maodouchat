package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.AiRequestGenerationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestGenerationGateTest {
    @Test
    fun `new request supersedes every older callback`() {
        val gate = AiRequestGenerationGate()
        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `explicit cancellation invalidates current request`() {
        val gate = AiRequestGenerationGate()
        val request = gate.next()

        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }
}
