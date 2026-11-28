package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CallSignalingOutboundModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-', '.', '|', 'é', '中', '文')
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomTicket(): Ticket =
        Ticket(epoch = randomLong(), sequence = randomLong(), idempotencyKey = randomString())

    @Test
    fun `ticket copy and equality over random fields`() {
        repeat(200) {
            val ticket = randomTicket()
            assertEquals(ticket, ticket.copy())
            assertEquals(ticket.hashCode(), ticket.copy().hashCode())
            assertEquals(ticket, ticket.copy(epoch = ticket.epoch))
            assertNotEquals(ticket, ticket.copy(epoch = ticket.epoch + 1))
            assertNotEquals(ticket, ticket.copy(sequence = ticket.sequence + 1))
            assertNotEquals(ticket, ticket.copy(idempotencyKey = ticket.idempotencyKey + "x"))
        }
    }

    @Test
    fun `ticket fields are retained verbatim`() {
        repeat(200) {
            val ticket = randomTicket()
            assertEquals(ticket, Ticket(ticket.epoch, ticket.sequence, ticket.idempotencyKey))
            assertEquals(ticket.epoch, ticket.copy().epoch)
            assertEquals(ticket.sequence, ticket.copy().sequence)
            assertEquals(ticket.idempotencyKey, ticket.copy().idempotencyKey)
        }
        val extremes = Ticket(epoch = Long.MIN_VALUE, sequence = Long.MAX_VALUE, idempotencyKey = "")
        assertEquals(Long.MIN_VALUE, extremes.epoch)
        assertEquals(Long.MAX_VALUE, extremes.sequence)
        assertEquals("", extremes.idempotencyKey)
    }

    @Test
    fun `ticket itself imposes no coercion on epoch or sequence`() {
        // 游标 begin() 负责把 epoch 钳到 >= 1；Ticket 只存值，不钳制。
        assertEquals(0L, Ticket(epoch = 0L, sequence = 0L, idempotencyKey = "").epoch)
        assertEquals(-5L, Ticket(epoch = -5L, sequence = -1L, idempotencyKey = "k").epoch)
        assertEquals(-1L, Ticket(epoch = 1L, sequence = -1L, idempotencyKey = "k").sequence)
    }
}
