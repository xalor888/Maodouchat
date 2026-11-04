package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDirectCipherModelsTest {

    @Test
    fun `envelope payload defaults to no ciphertexts`() {
        val payload = MultiRecipientEnvelopePayload(
            envelope = "env",
            targets = listOf(MultiRecipientDeviceTarget("u1", 1)),
        )
        assertEquals("env", payload.envelope)
        assertTrue(payload.ciphertexts.isEmpty())
    }

    @Test
    fun `device ciphertext round-trips fields`() {
        val c = DeviceCiphertext("u2", 3, "prekey", "b64")
        assertEquals("u2", c.userId)
        assertEquals(3, c.deviceId)
        assertEquals("prekey", c.ciphertextType)
        assertEquals("b64", c.ciphertext)
    }

    @Test
    fun `encrypted payload keeps type and bytes`() {
        val p = EncryptedPayload("signal", byteArrayOf(1, 2, 3))
        assertEquals("signal", p.type)
        assertEquals(3, p.payload.size)
    }
}
