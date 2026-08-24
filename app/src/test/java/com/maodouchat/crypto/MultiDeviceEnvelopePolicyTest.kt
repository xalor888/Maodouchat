package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDeviceEnvelopePolicyTest {

    private val omittedAlgorithmWire =
        """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""

    private val omittedAlgorithmWithRecipient =
        """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"recipientUserId":"alice","recipientDeviceId":188,"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""

    private val fullWire =
        """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":76,"payloadType":"TEXT","entries":[{"recipientUserId":"alice","recipientDeviceId":188,"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""

    @Test
    fun omittedAlgorithmAndDeviceIdStillParses() {
        val parsed = MultiDeviceEnvelopePolicy.parse(omittedAlgorithmWire)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.version)
        assertEquals(MultiDeviceEnvelopePolicy.ALGORITHM_SIGNAL_MULTI_DEVICE, parsed.algorithm)
        assertEquals(76, parsed.senderDeviceId)
        assertEquals("TEXT", parsed.payloadType)
        assertEquals(1, parsed.entries.size)
        assertTrue(parsed.entries.single().recipientDeviceIdOmitted)
        assertEquals("prekey", parsed.entries.single().ciphertextType)
        assertEquals("NAgB", parsed.entries.single().ciphertext)
    }

    @Test
    fun compactOmittedDeviceIdSelectsLocalDevice() {
        val parsed = MultiDeviceEnvelopePolicy.parse(omittedAlgorithmWire)!!
        val entry = MultiDeviceEnvelopePolicy.selectEntry(parsed, currentUserId = "alice", localDeviceId = 188)
        assertNotNull(entry)
        assertEquals(188, entry!!.recipientDeviceId)
        assertEquals("NAgB", entry.ciphertext)
    }

    @Test
    fun explicitRecipientDeviceMustMatch() {
        val parsed = MultiDeviceEnvelopePolicy.parse(omittedAlgorithmWithRecipient)!!
        assertNotNull(MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 188))
        assertNull(MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 76))
        assertNull(MultiDeviceEnvelopePolicy.selectEntry(parsed, "bob", 188))
    }

    @Test
    fun fullEnvelopeStillParses() {
        val parsed = MultiDeviceEnvelopePolicy.parse(fullWire)
        assertNotNull(parsed)
        assertFalse(parsed!!.entries.single().recipientDeviceIdOmitted)
        assertEquals(188, MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 188)?.recipientDeviceId)
    }

    @Test
    fun userJsonIsNotAnEnvelope() {
        assertNull(MultiDeviceEnvelopePolicy.parse("""{"hello":"world"}"""))
        assertNull(MultiDeviceEnvelopePolicy.parse("""{"senderDeviceId":1,"entries":[]}"""))
        assertFalse(MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire("""{"hello":"world"}"""))
        assertTrue(MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(omittedAlgorithmWire))
    }

    @Test
    fun foreignAlgorithmRejected() {
        val body =
            """{"version":3,"algorithm":"not-signal","senderDeviceId":1,"entries":[{"recipientDeviceId":1,"ciphertext":"x"}]}"""
        assertNull(MultiDeviceEnvelopePolicy.parse(body))
    }

    @Test
    fun twoOmittedDeviceEntriesAreNotGuessedAsThisDevice() {
        val body =
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"AAA="},{"ciphertextType":"prekey","ciphertext":"BBB="}]}"""
        val parsed = MultiDeviceEnvelopePolicy.parse(body)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.entries.size)
        assertTrue(parsed.entries.all { it.recipientDeviceIdOmitted })
        assertNull(MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 188))
    }

    @Test
    fun defaultDeviceOneFallsBackWhenLocalIsDefault() {
        val body =
            """{"senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientUserId":"alice","recipientDeviceId":1,"ciphertextType":"signal","ciphertext":"CCC="}]}"""
        val parsed = MultiDeviceEnvelopePolicy.parse(body)!!
        assertEquals("CCC=", MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 1)?.ciphertext)
        assertNull(MultiDeviceEnvelopePolicy.selectEntry(parsed, "alice", 188))
    }
}
