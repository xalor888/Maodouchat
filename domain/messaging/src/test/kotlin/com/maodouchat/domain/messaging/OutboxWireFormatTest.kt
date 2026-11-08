package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxWireFormatTest {

    @Test
    fun `lowercase crypto constants become uppercase on the wire`() {
        assertEquals("PREKEY", OutboxWireFormat.ciphertextType("prekey"))
        assertEquals("SIGNAL", OutboxWireFormat.ciphertextType("signal"))
    }

    @Test
    fun `already uppercase value passes through unchanged`() {
        assertEquals("SENDER_KEY", OutboxWireFormat.ciphertextType("SENDER_KEY"))
    }

    @Test
    fun `mixed case value is fully uppercased`() {
        assertEquals("PREKEY", OutboxWireFormat.ciphertextType("PreKey"))
    }
}
