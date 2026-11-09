package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxKindTest {

    @Test
    fun `kind values are the persisted wire vocabulary`() {
        assertEquals("DATA", OutboxKind.DATA)
        assertEquals("EVENT", OutboxKind.EVENT)
        assertEquals("RECEIPT", OutboxKind.RECEIPT)
        assertEquals("SENDER_KEY", OutboxKind.SENDER_KEY)
        assertEquals("KEY_REQUEST", OutboxKind.KEY_REQUEST)
    }

    @Test
    fun `all kinds are distinct`() {
        val kinds = listOf(
            OutboxKind.DATA,
            OutboxKind.EVENT,
            OutboxKind.RECEIPT,
            OutboxKind.SENDER_KEY,
            OutboxKind.KEY_REQUEST,
        )
        assertEquals(kinds.size, kinds.toSet().size)
        assertTrue(kinds.all { it.isNotBlank() })
    }
}
