package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDeviceEnvelopeModelsTest {

    @Test
    fun `parsed envelope round-trips entries`() {
        val entry = ParsedEntry("u1", 2, false, "msg", "ct")
        val env = ParsedEnvelope(1, "sig", 2, "TEXT", listOf(entry))
        assertEquals(1, env.version)
        assertEquals("sig", env.algorithm)
        assertEquals(1, env.entries.size)
        assertEquals("u1", env.entries[0].recipientUserId)
    }

    @Test
    fun `omitted device id and null type are representable`() {
        val entry = ParsedEntry(null, 0, true, null, "ct")
        assertNull(entry.recipientUserId)
        assertTrue(entry.recipientDeviceIdOmitted)
        assertNull(entry.ciphertextType)
    }
}
