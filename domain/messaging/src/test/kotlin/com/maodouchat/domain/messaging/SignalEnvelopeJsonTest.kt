package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.MultiDeviceMessageEntry
import com.maodouchat.core.crypto.SenderKeyDistributionEnvelope
import com.maodouchat.core.crypto.SenderKeyMessageEnvelope
import com.maodouchat.core.crypto.SignalProtocolConstants
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalEnvelopeJsonTest {

    private fun encryptedJson() = SignalEnvelopeJson.buildEncryptedMessageEnvelope(
        senderDeviceId = 1,
        recipientDeviceId = 2,
        ciphertextType = "signal",
        payloadType = "TEXT",
        ciphertext = "Y2lwaGVydGV4dA==",
    )

    private fun senderKeyJson(epoch: Long = 5) = SignalEnvelopeJson.buildUnsupportedSenderKeyEnvelope(
        groupId = "g-1",
        distributionId = "d-1",
        payloadType = "TEXT",
        ciphertext = "Y2lwaGVy",
        epoch = epoch,
    )

    @Test
    fun encryptedEnvelopeRoundTrip() {
        val json = encryptedJson()

        assertTrue(SignalEnvelopeJson.isEncryptedEnvelope(json))
        assertEquals("TEXT", SignalEnvelopeJson.envelopePayloadType(json))
        val parsed = SignalEnvelopeJson.parseEncryptedMessageEnvelope(json)
        assertNotNull(parsed)
        assertEquals(1, parsed.senderDeviceId)
        assertEquals(2, parsed.recipientDeviceId)
        assertEquals("signal", parsed.ciphertextType)
        assertEquals("Y2lwaGVydGV4dA==", parsed.ciphertext)
    }

    @Test
    fun garbageIsNotAnEncryptedEnvelope() {
        assertFalse(SignalEnvelopeJson.isEncryptedEnvelope("not json"))
        assertNull(SignalEnvelopeJson.envelopePayloadType("not json"))
        assertNull(SignalEnvelopeJson.parseEncryptedMessageEnvelope("not json"))
    }

    @Test
    fun multiDeviceEnvelopeRecognizedAsEncrypted() {
        val json = SignalEnvelopeJson.buildMultiDeviceMessageEnvelope(
            senderDeviceId = 1,
            payloadType = "TEXT",
            entries = listOf(
                MultiDeviceMessageEntry(recipientDeviceId = 2, ciphertext = "Y2lwaGVy"),
            ),
        )

        assertTrue(SignalEnvelopeJson.isEncryptedEnvelope(json))
        assertEquals("TEXT", SignalEnvelopeJson.envelopePayloadType(json))
    }

    @Test
    fun unsupportedSenderKeyEnvelopeIsSenderKeyEnvelope() {
        val json = senderKeyJson(epoch = 5)

        assertTrue(SignalEnvelopeJson.isSenderKeyEnvelope(json))
        assertEquals(5L, SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(json))
        val parsed = SignalEnvelopeJson.parseSenderKeyMessageEnvelope(json)
        assertNotNull(parsed)
        assertEquals("g-1", parsed.groupId)
        assertEquals("d-1", parsed.distributionId)
        assertEquals("TEXT", parsed.payloadType)
    }

    @Test
    fun zeroEpochParsesToNull() {
        assertNull(SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(senderKeyJson(epoch = 0)))
    }

    @Test
    fun epochParsingRejectsGarbageAndWrongShape() {
        assertNull(SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch("garbage"))
        assertNull(SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(encryptedJson()))
    }

    @Test
    fun senderKeyDistributionEnvelopeRoundTrip() {
        val json = SignalProtocolConstants.json.encodeToString(
            SenderKeyDistributionEnvelope(
                groupId = "g-1",
                epoch = 3,
                distributionId = "d-1",
                distributionMessage = "QUFBQQ==",
            ),
        )

        assertTrue(SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json))
        val parsed = SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(json)
        assertNotNull(parsed)
        assertEquals(3L, parsed.epoch)
        assertEquals("d-1", parsed.distributionId)
    }

    @Test
    fun wrongAlgorithmIsNotSenderKeyDistributionEnvelope() {
        val json = SignalProtocolConstants.json.encodeToString(
            SenderKeyDistributionEnvelope(
                groupId = "g-1",
                distributionId = "d-1",
                distributionMessage = "QUFBQQ==",
            ).copy(algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL),
        )

        assertFalse(SignalEnvelopeJson.isSenderKeyDistributionEnvelope(json))
    }

    @Test
    fun wrongVersionIsNotSenderKeyEnvelope() {
        val json = SignalProtocolConstants.json.encodeToString(
            SenderKeyMessageEnvelope(
                version = 99,
                groupId = "g-1",
                distributionId = "d-1",
                payloadType = "TEXT",
                ciphertext = "Y2lwaGVy",
            ),
        )

        assertFalse(SignalEnvelopeJson.isSenderKeyEnvelope(json))
        assertFalse(SignalEnvelopeJson.isSenderKeyEnvelope(encryptedJson()))
    }
}
