package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvelopeKindTest {

    @Test
    fun `kind values are the persisted wire vocabulary`() {
        assertEquals("SENDER_KEY", EnvelopeKind.SENDER_KEY)
        assertEquals("SERVICE", EnvelopeKind.SERVICE)
    }

    @Test
    fun `all kinds are distinct`() {
        val kinds = listOf(
            EnvelopeKind.SENDER_KEY,
            EnvelopeKind.SERVICE,
        )
        assertEquals(kinds.size, kinds.toSet().size)
        assertTrue(kinds.all { it.isNotBlank() })
    }
}
