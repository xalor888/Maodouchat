package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.AiRequestGenerationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `first tokens are consecutive starting at one`() {
        val gate = AiRequestGenerationGate()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), List(5) { gate.next() })
    }

    @Test
    fun `invalidate skips exactly one generation that can never become current`() {
        val gate = AiRequestGenerationGate()
        val token = gate.next()

        gate.invalidate()
        val skipped = token + 1
        val fresh = gate.next()

        assertFalse(gate.isCurrent(token))
        assertFalse(gate.isCurrent(skipped))
        assertEquals(skipped + 1, fresh)
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun `invalidate after parallel next drops every handed-out token`() {
        val gate = AiRequestGenerationGate()
        val tokens = (1..16).toList().parallelStream().map { gate.next() }.toList()

        gate.invalidate()

        assertTrue(tokens.none { gate.isCurrent(it) })
        assertTrue(gate.isCurrent(gate.next()))
    }

    @Test
    fun `isCurrent is stable across repeated checks`() {
        val gate = AiRequestGenerationGate()
        val token = gate.next()
        repeat(100) { assertTrue(gate.isCurrent(token)) }

        val stale = token
        gate.next()
        repeat(50) { assertFalse(gate.isCurrent(stale)) }
    }

    @Test
    fun `fresh token never equals a pre-invalidate token`() {
        val gate = AiRequestGenerationGate()
        val token = gate.next()

        gate.invalidate()
        val fresh = gate.next()

        assertNotEquals(token, fresh)
        assertEquals(token + 2, fresh)
    }

    @Test
    fun `two gates hand out independent token sequences`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()

        assertEquals(1L, first.next())
        assertEquals(1L, second.next())
        assertEquals(2L, first.next())

        assertFalse(first.isCurrent(1L))
        assertTrue(first.isCurrent(2L))
        assertTrue(second.isCurrent(1L))
    }

    @Test
    fun `double invalidate before the first request skips two generations`() {
        val gate = AiRequestGenerationGate()

        gate.invalidate()
        gate.invalidate()
        val fresh = gate.next()

        assertEquals(3L, fresh)
        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.isCurrent(2L))
        assertTrue(gate.isCurrent(3L))
    }

    @Test
    fun `invalidate after a run of requests leaves only the skipped generation unreachable`() {
        val gate = AiRequestGenerationGate()
        val tokens = List(5) { gate.next() }

        gate.invalidate()
        val fresh = gate.next()

        assertEquals(7L, fresh)
        assertTrue(tokens.none { gate.isCurrent(it) })
        assertFalse(gate.isCurrent(6L))
        assertTrue(gate.isCurrent(7L))
    }

    @Test
    fun `the same numeric token belongs to only its own gate`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()

        first.next()
        first.next()
        second.next()

        assertFalse(first.isCurrent(1L))
        assertTrue(second.isCurrent(1L))
    }

    @Test
    fun `invalidate never resurrects a superseded token`() {
        val gate = AiRequestGenerationGate()
        val stale = gate.next()
        gate.next()

        gate.invalidate()
        val fresh = gate.next()
        gate.invalidate()
        val newer = gate.next()

        assertFalse(gate.isCurrent(stale))
        assertFalse(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(5L))
        assertEquals(6L, newer)
        assertTrue(gate.isCurrent(newer))
    }

    @Test
    fun `interleaved invalidate keeps only the newest token current`() {
        val gate = AiRequestGenerationGate()
        val first = gate.next()
        gate.invalidate()
        val second = gate.next()
        val third = gate.next()
        gate.invalidate()
        val fourth = gate.next()

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(2L))
        assertFalse(gate.isCurrent(second))
        assertFalse(gate.isCurrent(third))
        assertFalse(gate.isCurrent(5L))
        assertEquals(6L, fourth)
        assertTrue(gate.isCurrent(fourth))
    }

    @Test
    fun `extreme token values are never current on a fresh gate`() {
        val gate = AiRequestGenerationGate()
        val current = gate.next()

        assertFalse(gate.isCurrent(Long.MIN_VALUE))
        assertFalse(gate.isCurrent(Long.MAX_VALUE))
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun `invalidate then two requests leaves only the second current`() {
        val gate = AiRequestGenerationGate()

        gate.invalidate()
        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.isCurrent(first))
        assertEquals(3L, second)
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `parallel invalidates still yield unique next tokens with one current`() {
        val gate = AiRequestGenerationGate()

        (1..32).toList().parallelStream().forEach { gate.invalidate() }
        val tokens = (1..32).toList().parallelStream().map { gate.next() }.toList()

        assertEquals(32, tokens.distinct().size)
        assertEquals(64L, tokens.max())
        assertEquals(1, tokens.count { gate.isCurrent(it) })
        assertTrue(gate.isCurrent(64L))
    }

    @Test
    fun `repeated next invalidate cycles keep the counter monotonic`() {
        val gate = AiRequestGenerationGate()
        var last = 0L
        repeat(100) {
            val token = gate.next()
            assertTrue(token > last)
            last = token
            gate.invalidate()
            assertFalse(gate.isCurrent(token))
        }

        val fresh = gate.next()
        assertEquals(201L, fresh)
        assertTrue(gate.isCurrent(fresh))
    }
    @Test
    fun `invalidate on a fresh gate makes the first token two`() {
        val gate = AiRequestGenerationGate()

        gate.invalidate()
        val first = gate.next()

        assertEquals(2L, first)
        assertFalse(gate.isCurrent(1L))
        assertTrue(gate.isCurrent(2L))
    }

    @Test
    fun `double invalidate after a request skips two generations`() {
        val gate = AiRequestGenerationGate()
        val token = gate.next()

        gate.invalidate()
        gate.invalidate()
        val fresh = gate.next()

        assertEquals(1L, token)
        assertEquals(4L, fresh)
        assertFalse(gate.isCurrent(2L))
        assertFalse(gate.isCurrent(3L))
        assertTrue(gate.isCurrent(4L))
    }

    @Test
    fun `invalidating one gate leaves another gate untouched`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()

        first.invalidate()
        val firstToken = first.next()
        val secondToken = second.next()

        assertEquals(2L, firstToken)
        assertEquals(1L, secondToken)
        assertTrue(first.isCurrent(2L))
        assertFalse(first.isCurrent(1L))
        assertTrue(second.isCurrent(1L))
        assertFalse(second.isCurrent(2L))
    }

    @Test
    fun `one thousand sequential tokens increase by one and only the last is current`() {
        val gate = AiRequestGenerationGate()
        val tokens = List(1000) { gate.next() }

        assertEquals((1L..1000L).toList(), tokens)
        assertTrue(gate.isCurrent(1000L))
        assertFalse(gate.isCurrent(999L))
    }

    @Test
    fun `three requests after an invalidate leave only the third current`() {
        val gate = AiRequestGenerationGate()
        val stale = List(5) { gate.next() }

        gate.invalidate()
        val fresh = List(3) { gate.next() }

        assertEquals(listOf(7L, 8L, 9L), fresh)
        assertTrue(stale.none { gate.isCurrent(it) })
        assertFalse(gate.isCurrent(6L))
        assertFalse(gate.isCurrent(7L))
        assertFalse(gate.isCurrent(8L))
        assertTrue(gate.isCurrent(9L))
    }


    @Test
    fun `each request invalidated between requests leaves only the last current`() {
        val gate = AiRequestGenerationGate()
        val first = gate.next()
        gate.invalidate()
        val second = gate.next()
        gate.invalidate()
        val third = gate.next()

        assertEquals(1L, first)
        assertEquals(3L, second)
        assertEquals(5L, third)
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(2L))
        assertFalse(gate.isCurrent(second))
        assertFalse(gate.isCurrent(4L))
        assertTrue(gate.isCurrent(third))
    }

    @Test
    fun `one hundred sequential invalidates skip one hundred generations`() {
        val gate = AiRequestGenerationGate()

        repeat(100) { gate.invalidate() }
        val fresh = gate.next()

        assertEquals(101L, fresh)
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.isCurrent(50L))
        assertFalse(gate.isCurrent(100L))
    }

    @Test
    fun `ten thousand tokens then invalidate leaves only the next token current`() {
        val gate = AiRequestGenerationGate()
        val tokens = List(10000) { gate.next() }

        gate.invalidate()
        val fresh = gate.next()

        assertEquals(10002L, fresh)
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(10000L))
        assertFalse(gate.isCurrent(10001L))
        assertTrue(tokens.none { gate.isCurrent(it) })
    }

    @Test
    fun `repeated isCurrent checks never advance the counter`() {
        val gate = AiRequestGenerationGate()
        val token = gate.next()

        repeat(100) { assertTrue(gate.isCurrent(token)) }
        repeat(100) { assertFalse(gate.isCurrent(token + 1)) }

        assertEquals(2L, gate.next())
        assertTrue(gate.isCurrent(2L))
    }

    @Test
    fun `interleaved invalidates on two gates stay independent`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()

        first.invalidate()
        val firstToken = first.next()
        val secondToken = second.next()
        second.invalidate()
        val secondFresh = second.next()
        val firstFresh = first.next()

        assertEquals(2L, firstToken)
        assertEquals(1L, secondToken)
        assertEquals(3L, secondFresh)
        assertEquals(3L, firstFresh)
        assertTrue(first.isCurrent(3L))
        assertTrue(second.isCurrent(3L))
        assertFalse(first.isCurrent(2L))
        assertFalse(second.isCurrent(1L))
    }

    @Test
    fun `one thousand sequential invalidates skip one thousand generations`() {
        val gate = AiRequestGenerationGate()

        repeat(1000) { gate.invalidate() }
        val fresh = gate.next()

        assertEquals(1001L, fresh)
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.isCurrent(500L))
        assertFalse(gate.isCurrent(1000L))
    }

    @Test
    fun `parallel next and invalidate batches keep at most one token current`() {
        val gate = AiRequestGenerationGate()

        val tokens = (1..32).toList().parallelStream().map { gate.next() }.toList()
        (1..32).toList().parallelStream().forEach { gate.invalidate() }

        assertTrue(tokens.count { gate.isCurrent(it) } <= 1)
        assertEquals(65L, gate.next())
    }

    @Test
    fun `fifty next invalidate pairs advance the counter by one hundred`() {
        val gate = AiRequestGenerationGate()

        repeat(50) {
            gate.next()
            gate.invalidate()
        }
        val fresh = gate.next()

        assertEquals(101L, fresh)
        assertFalse(gate.isCurrent(100L))
        assertTrue(gate.isCurrent(101L))
    }

    @Test
    fun `skipped generations across a long run are never current`() {
        val gate = AiRequestGenerationGate()
        val tokens = List(10) { gate.next() }

        gate.invalidate()
        val fresh = gate.next()

        assertEquals(12L, fresh)
        assertTrue(tokens.none { gate.isCurrent(it) })
        assertFalse(gate.isCurrent(11L))
        assertTrue(gate.isCurrent(12L))
    }

    @Test
    fun `repeated next invalidate cycles advance exactly two per cycle`() {
        val gate = AiRequestGenerationGate()

        repeat(10) {
            val token = gate.next()
            gate.invalidate()
            val fresh = gate.next()

            assertEquals(token + 2, fresh)
            assertTrue(gate.isCurrent(fresh))
        }
    }

    @Test
    fun `seven invalidates then the next token is eight`() {
        val gate = AiRequestGenerationGate()

        repeat(7) { gate.invalidate() }
        val fresh = gate.next()

        assertEquals(8L, fresh)
        assertFalse(gate.isCurrent(7L))
        assertFalse(gate.isCurrent(0L))
        assertTrue(gate.isCurrent(8L))
    }

    @Test
    fun `three gates with different counts keep different current tokens`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()
        val third = AiRequestGenerationGate()

        first.next()
        second.next()
        second.next()
        third.next()
        third.next()
        third.next()

        assertTrue(first.isCurrent(1L))
        assertTrue(second.isCurrent(2L))
        assertTrue(third.isCurrent(3L))
        assertFalse(first.isCurrent(2L))
        assertFalse(second.isCurrent(1L))
        assertFalse(second.isCurrent(3L))
        assertFalse(third.isCurrent(2L))
    }

    @Test
    fun `two requests then an invalidate then a request leaves only the fourth token current`() {
        val gate = AiRequestGenerationGate()
        val first = gate.next()
        val second = gate.next()

        gate.invalidate()
        val fresh = gate.next()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(4L, fresh)
        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.isCurrent(2L))
        assertFalse(gate.isCurrent(3L))
        assertTrue(gate.isCurrent(4L))
    }

    @Test
    fun `parallel next calls on two gates each produce one through eight with only eight current`() {
        val first = AiRequestGenerationGate()
        val second = AiRequestGenerationGate()

        val firstTokens = (1..8).toList().parallelStream().map { first.next() }.toList().sorted()
        val secondTokens = (1..8).toList().parallelStream().map { second.next() }.toList().sorted()

        assertEquals((1L..8L).toList(), firstTokens)
        assertEquals((1L..8L).toList(), secondTokens)
        assertTrue(first.isCurrent(8L))
        assertTrue(second.isCurrent(8L))
        assertFalse(first.isCurrent(9L))
        assertFalse(second.isCurrent(9L))
    }

    @Test
    fun `isCurrent with negative and extreme values stays false after two requests`() {
        val gate = AiRequestGenerationGate()
        gate.next()
        gate.next()

        assertFalse(gate.isCurrent(0L))
        assertFalse(gate.isCurrent(-1L))
        assertFalse(gate.isCurrent(Long.MIN_VALUE))
        assertFalse(gate.isCurrent(Long.MAX_VALUE))
        assertFalse(gate.isCurrent(3L))
        assertTrue(gate.isCurrent(2L))
    }
}
