package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.AiRequestGenerationGate
import org.junit.Assert.assertEquals
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

    @Test
    fun `tokens are unique and strictly increasing`() {
        val gate = AiRequestGenerationGate()
        val tokens = List(10) { gate.next() }

        assertEquals(tokens.size, tokens.distinct().size)
        assertEquals(tokens.sorted(), tokens)
    }

    @Test
    fun `unknown tokens are never current`() {
        val gate = AiRequestGenerationGate()
        val current = gate.next()

        assertFalse(gate.isCurrent(0L))
        assertFalse(gate.isCurrent(-1L))
        assertFalse(gate.isCurrent(current + 1))
    }

    @Test
    fun `invalidate before any request leaves the next token valid`() {
        val gate = AiRequestGenerationGate()

        gate.invalidate()
        val request = gate.next()

        assertTrue(gate.isCurrent(request))
    }

    @Test
    fun `repeated invalidation still leaves only the latest token current`() {
        val gate = AiRequestGenerationGate()
        val stale = gate.next()

        gate.invalidate()
        gate.invalidate()
        val fresh = gate.next()

        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun `concurrent next() calls never hand out the same token`() {
        val gate = AiRequestGenerationGate()

        val tokens = (1..16).toList().parallelStream().map { gate.next() }.toList()

        assertEquals(16, tokens.distinct().size)
    }
}
